# TalentHub 培训课程抢课报名 —— 详细设计方案

> 版本：v1.1（2026-08-29，数据库选型调整为 PostgreSQL）
> 场景：内部 toB 人力资源系统的培训课程限额报名（类秒杀，但规模远小于电商秒杀）
> 核心目标：**不超卖（硬约束）、尽量不少卖（软约束）、单用户单课程只报一次（硬约束）**

---

## 1. 总体架构

```
用户请求
   │
   ▼
┌─────────────┐   接口限流（令牌桶，阈值≈库存×1.5）
│  网关/应用层  │   参数校验、登录态校验
└──────┬──────┘
       ▼
┌─────────────┐   Lua 脚本原子执行三件事：
│    Redis     │   ① 用户去重  ② 库存判断  ③ 预扣库存
└──────┬──────┘   拒绝的请求在此终结，不触达 DB
       ▼ 仅"预扣成功"的请求
┌─────────────┐   单事务：INSERT ON CONFLICT 报名 + 条件 UPDATE 扣库存
│ PostgreSQL  │   行锁 + stock > 0 + 唯一约束，超卖的最终防线
└──────┬──────┘
       │ DB 失败 ──► 同步回补 Redis（主路径回滚）
       ▼
┌─────────────┐
│  对账补偿任务 │   定时核对 Redis 与 DB 库存，以 DB 为准单向修正（兜底）
└─────────────┘
```

两层防线的职责划分：

| 层 | 职责 | 失效后果 |
|---|---|---|
| Redis + Lua | 挡掉绝大多数无效请求（重复用户、库存已尽），保护 DB | 请求全量打到 DB，压力上升但**不会超卖** |
| PostgreSQL | 最终一致性的权威数据源，条件更新防超卖，唯一约束防重复 | 无（最后防线） |

设计原则：**任何不一致只允许表现为"少卖"，绝不允许"超卖"或"一人多单"。**

---

## 2. 数据库设计

### 2.1 表结构

```sql
-- 课程表（含库存）
CREATE TABLE course (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title         VARCHAR(128) NOT NULL,
    total_quota   INT          NOT NULL,
    stock         INT          NOT NULL CHECK (stock >= 0),
    enroll_start  TIMESTAMPTZ  NOT NULL,
    enroll_end    TIMESTAMPTZ  NOT NULL,
    status        SMALLINT     NOT NULL DEFAULT 0,
    version       INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
) WITH (fillfactor = 70);   -- 热点行高频 UPDATE，页内预留空间促成 HOT 更新，见 §2.2

COMMENT ON TABLE  course              IS '培训课程';
COMMENT ON COLUMN course.total_quota  IS '总名额';
COMMENT ON COLUMN course.stock        IS '剩余名额';
COMMENT ON COLUMN course.status       IS '0-未开始 1-报名中 2-已结束 3-已取消';

-- 报名记录表
CREATE TABLE enrollment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    course_id   BIGINT      NOT NULL,
    status      SMALLINT    NOT NULL DEFAULT 1,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    canceled_at TIMESTAMPTZ NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- ★ 风险点2的DB防线：同一用户同一课程物理上只允许一条记录
    CONSTRAINT uk_user_course UNIQUE (user_id, course_id)
);
CREATE INDEX idx_enrollment_course ON enrollment (course_id);

COMMENT ON TABLE  enrollment        IS '报名记录';
COMMENT ON COLUMN enrollment.status IS '1-已报名 2-已取消';
```

设计说明：

- **`uk_user_course` 唯一约束是"一人一单"的最终保证**。取消报名不删除记录，而是把 `status` 置为 2；用户重新报名时由主流程的 `INSERT ... ON CONFLICT` upsert 统一覆盖（见 §4.1），避免"删除再插入"与唯一约束的复杂交互。
- `stock` 上的 `CHECK (stock >= 0)` 是防御性冗余（PostgreSQL 各版本均强制执行），主防线是条件 UPDATE。
- PostgreSQL 没有 `ON UPDATE CURRENT_TIMESTAMP`，`updated_at` 由应用层（MyBatis/JPA 拦截器）统一维护，不引入触发器。
- `total_quota` 与 `stock` 分开存，对账任务可以用 `total_quota - 有效报名数` 交叉验证 `stock` 的正确性。

### 2.2 扣库存 SQL（风险点 3：单行热点的处理）

**必须使用单条原子条件 UPDATE，禁止 `SELECT ... FOR UPDATE` 再 UPDATE：**

```sql
UPDATE course
SET stock = stock - 1
WHERE id = #{courseId}
  AND stock > 0
  AND status = 1;
-- 返回 affected rows = 1 → 扣减成功；= 0 → 已售罄或状态不对，报名失败
```

理由：同一课程的所有成功请求最终串行竞争同一行的行锁，锁持有时间直接决定吞吐上限。
`SELECT FOR UPDATE` + `UPDATE` 的锁持有跨两次网络往返；单条条件 UPDATE 把判断和扣减合并进一次加锁窗口内，锁持有时间减半以上。

配套的事务纪律：

- "扣库存 + 插报名记录"放在**同一个尽量短的事务**里，事务内**禁止任何 RPC、Redis 调用、慢查询、日志落库**。
- 事务内语句顺序：**先对 enrollment 做报名 upsert，后 UPDATE course**。热点行的行锁在事务里最后获取、随提交立即释放，进一步压缩热点行锁的持有时间；且重复报名（upsert affected = 0）在第一步就终止，根本不去竞争热点行锁。
- 应用层为报名接口配置独立的、有上限的 DB 连接池（例如 20~50），配合前端限流，防止热点行排队拖垮整个连接池。

**PostgreSQL 特有的热点行注意事项（MVCC 表膨胀）：**

PostgreSQL 的 UPDATE 不是原地更新，每次都会产生一个新的行版本（dead tuple 留待 vacuum 回收）。抢课高峰期同一行被高频更新，会造成 `course` 表页面膨胀、索引写放大。对策：

- `course` 表建表时设置 `fillfactor = 70`（已写入 DDL），页内预留空间，使更新尽量走 **HOT（Heap-Only Tuple）路径**，不触碰索引；
- 配套纪律：**`stock`、`version`、`updated_at` 这些高频变更列上不建任何索引**，否则 HOT 失效；
- 对 `course` 表单独调低 autovacuum 阈值，让死元组及时回收：

```sql
ALTER TABLE course SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_vacuum_cost_delay   = 0
);
```

> 扩展预留：若未来并发增长 10 倍以上，演进方向是**库存分桶**（一行拆 N 行随机扣减）。当前规模不做，表结构无需为此预留字段。

---

## 3. Redis 设计

### 3.1 Key 设计

| Key | 类型 | 含义 | 过期 |
|---|---|---|---|
| `course:stock:{courseId}` | String（整数） | 该课程 Redis 侧剩余库存 | 报名截止后 7 天 |
| `course:users:{courseId}` | Set | 已成功预扣的用户 ID 集合 | 报名截止后 7 天 |

- 按课程维度拆 key，多课程同时开抢天然分散，无热点 key 问题。
- 过期时间统一在**报名截止时间 + 7 天**设置（留给对账与查询），不用滑动过期。

### 3.2 抢课 Lua 脚本（风险点 2：去重 + 判断 + 扣减三合一原子执行）

文件：`scripts/redis/enroll.lua`，随应用启动 `SCRIPT LOAD`，代码仓库统一管理版本。

```lua
-- KEYS[1] = course:stock:{courseId}
-- KEYS[2] = course:users:{courseId}
-- ARGV[1] = userId
-- 返回值: 1=预扣成功  0=库存不足  -1=重复报名  -2=库存未预热

-- 库存 key 不存在 → 未预热或已过期，明确拒绝，绝不当作 0 或无限处理
if redis.call('EXISTS', KEYS[1]) == 0 then
    return -2
end

-- 用户去重：已在集合中 → 重复请求，直接拒绝，不扣库存
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- 库存判断
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock <= 0 then
    return 0
end

-- 判断通过后才写入：预扣库存 + 记录用户，两个写操作同脚本原子生效
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
```

要点：

- **三个判断/动作在同一脚本内原子完成**，不存在"判断通过后被并发插队"的窗口。
- 同一用户连点、脚本刷请求：第一次成功后，后续全部命中 `SISMEMBER` 返回 -1，**不会重复扣减 Redis 库存**——这是风险点 2 在 Redis 侧的防线（DB 侧防线是唯一索引）。
- `EXISTS` 前置判断处理"未预热/key 过期"的语义：宁可拒绝报名（少卖，可运维介入），不可放行导致行为未定义。
- 脚本保持极简，业务规则（时间窗校验、资格校验）留在应用层，在调用脚本**之前**完成。

### 3.3 回补 Lua 脚本（供主路径回滚与退课使用）

文件：`scripts/redis/rollback.lua`。回补必须"库存 +1"与"移出用户集合"原子成对执行，且幂等：

```lua
-- KEYS[1] = course:stock:{courseId}
-- KEYS[2] = course:users:{courseId}
-- ARGV[1] = userId
-- 返回值: 1=回补成功  0=该用户本无预扣记录（幂等空操作）

if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0    -- key 已过期，无需回补，对账任务兜底
end
-- SREM 返回 1 才说明确实持有预扣，才允许 INCR，保证回补不会把库存加多
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
return 0
```

以 `SREM` 的返回值作为回补资格判断，天然幂等：重试多少次都不会把库存多加。

---

## 4. 核心流程

### 4.1 报名主流程（含风险点 1 的同步回补）

```
① 应用层前置校验：登录态、报名时间窗、课程状态（读本地缓存/Redis，不读 DB）
② 执行 enroll.lua (courseId, userId)
      ├─ -2 → 返回"报名未开放"，并触发预热告警
      ├─ -1 → 返回"您已报名该课程"（对用户是成功语义，见 §4.2）
      ├─  0 → 返回"名额已满"
      └─  1 → 继续 ③
③ DB 事务（先报名 upsert，后条件 UPDATE，见 §2.2）：
      -- PG 的 ON CONFLICT 把"新报名 / 曾取消后重新报名 / 重复报名"三种情况合并为一条语句
      INSERT INTO enrollment(user_id, course_id, status)
      VALUES (?, ?, 1)
      ON CONFLICT (user_id, course_id)
      DO UPDATE SET status = 1, enrolled_at = now(), canceled_at = NULL
      WHERE enrollment.status = 2;
      -- affected = 1 → 新报名或重新报名成功，继续
      -- affected = 0 → 该用户已持有 status=1 记录（重复报名）→ 回滚，走 §4.3 分支A
      UPDATE course SET stock = stock - 1 WHERE id = ? AND stock > 0 AND status = 1;
      -- affected = 0 → 回滚整个事务
      ├─ 提交成功 → 返回"报名成功"
      ├─ upsert affected = 0（用户已报名） → 见 §4.3 分支A
      ├─ UPDATE affected = 0（DB 售罄）   → 见 §4.3 分支B
      └─ 其他异常（超时/连接失败等）     → 见 §4.3 分支C
```

**关键原则（风险点 1）：只要 ② 预扣成功而 ③ 未确认提交成功，主路径必须同步执行回补，
不得把回补留给定时任务。** 定时对账只是兜底，兜的是"回补本身也失败"的小概率残留。

### 4.2 各返回码的用户语义

| 场景 | 用户看到 |
|---|---|
| Lua 返回 1 且 DB 成功 | 报名成功 |
| Lua 返回 -1（Redis 判重）| "您已报名该课程"——重复点击/重试的正常兜底，**不算错误** |
| Lua 返回 0 | 名额已满 |
| DB 失败且回补成功 | "系统繁忙，请重试"（名额已还回去，用户重试仍有机会） |
| DB 失败且回补也失败 | 同上提示；差异由对账任务在 1 分钟内修复 |

### 4.3 DB 失败的三个分支

**分支 A：报名 upsert affected = 0（用户已持有 `status = 1` 记录）**

`ON CONFLICT` upsert 已把"曾取消后重新报名"在语句内正常吸收（affected = 1），所以 affected = 0 只剩一种含义：该用户已报名（并发双请求都过了 Redis 判重的极端窗口，或历史已报名但 Redis 集合丢失）。处理：回滚事务 → 调用 `rollback.lua` 回补本次预扣 → **重新 `SADD` 该用户回集合**（他确实占着名额，集合状态要与 DB 对齐）→ 返回"您已报名该课程"。

相比依赖唯一约束异常（SQLSTATE 23505）再补查记录状态的写法，upsert 方案无异常路径、无额外查询，且并发下语义由 PG 保证。唯一约束仍保留，作为绕过主流程的写入（管理后台、数据订正）的最后防线。

**分支 B：条件 UPDATE affected = 0（DB 售罄，Redis 与 DB 库存不一致）**

事务回滚（INSERT 一并撤销）→ 同步调用 `rollback.lua` → 返回"名额已满" → 记录不一致告警日志（此情况说明 Redis 库存虚高，需要对账任务修正，见 §5）。

**分支 C：超时 / 连接异常等未知结果**

注意超时时事务**可能实际已提交**（结果未知），不能盲目回补：

1. 先按 `(user_id, course_id, status=1)` 反查 enrollment 确认真实结果；
2. 查到记录 → 实际成功，返回"报名成功"；
3. 确认无记录 → 调用 `rollback.lua` 回补，返回"系统繁忙，请重试"；
4. 反查也失败 → 不回补（宁可暂时少卖），打不一致告警日志，留给对账任务，返回"系统繁忙"。

回补调用自身失败（Redis 抖动）：本地重试 3 次（间隔 100ms），仍失败则写告警日志 + 留给对账任务。回补脚本幂等，重试安全。

### 4.4 取消报名 / 退课流程

```
① DB 事务：
      UPDATE enrollment SET status = 2, canceled_at = NOW()
       WHERE user_id = ? AND course_id = ? AND status = 1;      -- affected=0 → 无可取消，直接返回
      UPDATE course SET stock = stock + 1 WHERE id = ? AND stock < total_quota;
② DB 提交成功后，调用 rollback.lua 回补 Redis（+1 库存、移出用户集合）
③ ② 失败 → 告警日志 + 对账任务兜底
```

曾取消用户的**重新报名**：无需单独路径——主流程 §4.1 的 `INSERT ... ON CONFLICT` upsert 已统一覆盖，应用层不需要预查记录状态。

顺序纪律：**报名是"先扣 Redis 后写 DB"，取消是"先写 DB 后回补 Redis"**——两个方向都保证中间态只可能表现为"Redis 库存 ≤ 真实可卖量"，即只少卖不超卖。

---

## 5. 对账补偿任务（风险点 1 的兜底层）

- **定位**：兜底，不是主路径。修复的是"主路径同步回补也失败"的残留差异，正常情况下每轮对账应当发现 0 差异。
- **频率**：报名活动进行中每 1 分钟一轮；非活动期每 30 分钟一轮。
- **基准**：**以 DB 为准**。`DB 真实库存 = course.stock`，交叉验证 `total_quota - COUNT(enrollment WHERE status=1)`，两者不等先告警（说明 DB 自身有 bug，停止本轮修正）。

**单向修正规则（宁少卖不超卖）：**

| 对账发现 | 动作 |
|---|---|
| Redis 库存 > DB 库存（Redis 虚高，会放多余请求打 DB）| **立即修正**：将 Redis 库存 `SET` 为 DB 值，同时按 DB 报名名单重建 `course:users` 集合 |
| Redis 库存 < DB 库存（Redis 虚低，表现为少卖）| 差值 ≤ 阈值(如 3)且持续 < 2 轮 → 仅记录（可能是在途请求）；持续存在 → 修正为 DB 值并告警 |
| 差值任意方向 ≥ 告警阈值(如 5) | 修正 + 电话/IM 告警人工介入 |

- **与在途请求的并发**："Redis 虚低"方向的修正必须容忍在途请求造成的瞬时差异，所以采用"连续两轮仍存在才修正"的策略，避免把正常在途状态误纠。"虚高"方向直接修，因为最坏效果只是多几个请求被 DB 的 `stock > 0` 拦住。
- 修正动作全部写审计日志（修正前后值、依据、时间），对账任务不允许静默纠错。

---

## 6. 库存预热与活动生命周期

| 阶段 | 动作 |
|---|---|
| 活动创建/开抢前 5 分钟 | 定时任务把 `course.stock` 写入 `course:stock:{id}`，按 DB 已报名名单初始化 `course:users:{id}`（处理提前报名/重开场景），设置过期时间 = 截止时间 + 7 天 |
| 预热校验 | 预热完成后回读校验，失败重试并告警；未预热成功不打开报名入口（Lua 的 -2 是最后一道防线） |
| 开抢 | `course.status` 置 1，前端放开按钮 |
| 截止 | `course.status` 置 2，应用层时间窗校验拦截，Redis key 留待对账后自然过期 |
| 管理员改名额 | 修改 `total_quota`/`stock` 必须走统一入口：DB 更新成功后同步刷 Redis，禁止只改一边 |

---

## 7. 限流与降级

**限流（保护 DB，不承担防超卖职责）：**

- 接口级：网关/应用层令牌桶，阈值 ≈ 单课程库存 × 1.5（库存 100 → 峰值放行约 150 QPS 到 Lua 层），超出直接返回"当前人数过多"。
- 用户级：同一用户对同一课程 2 秒内最多 1 次请求（Redis `SET NX EX` 即可），挡连点与脚本。

**Redis 故障降级（预案提前定死，禁止临场决策）：**

- Lua 调用异常/超时 → 熔断器打开，进入降级模式：请求**直接走 DB 事务路径**（跳过 ②）。DB 的条件 UPDATE + 唯一索引保证仍然**不超卖、不重单**，只是 DB 压力上升——toB 规模下配合接口限流可承受。
- 降级模式下把接口限流阈值自动下调（如降到 50 QPS），并触发告警。
- Redis 恢复后：**先跑一轮对账重建 Redis 数据，再关闭降级**，顺序不能反。

---

## 8. 监控与告警

| 指标 | 告警条件 |
|---|---|
| Redis 库存 与 DB 库存差值 | 绝对值 ≥ 5 或连续 2 轮非 0 |
| 主路径同步回补失败次数 | ≥ 1 即告警（正常应为 0） |
| 对账任务修正次数 | ≥ 1 即通知（正常应为 0） |
| 报名事务 P99 耗时 / DB 连接池等待数 | 超阈值（热点行锁排队的早期信号） |
| Lua 返回 -2 次数 | ≥ 1（预热失败信号） |
| 降级模式开关状态 | 变更即告警 |

---

## 9. 三个关键风险点的落地对照表

| 风险点 | 防线 | 落地位置 |
|---|---|---|
| ① Redis/DB 不一致窗口 | 主路径 DB 失败**同步回补**（幂等脚本）；未知结果先反查再回补；对账任务以 DB 为准单向兜底 | §3.3、§4.3、§5 |
| ② 幂等与去重 | Redis 侧：Lua 内 `SISMEMBER`/`SADD` 与扣减原子一体；DB 侧：`uk_user_course` 唯一约束 + `ON CONFLICT` upsert 兜底分支 | §2.1、§3.2、§4.1、§4.3-A |
| ③ DB 单行热点 | 单条条件 UPDATE（不用 `SELECT FOR UPDATE`）；短事务、先 upsert 后 UPDATE；`fillfactor` + HOT 更新 + autovacuum 调优应对 MVCC 膨胀；独立受限连接池；分桶仅作演进预留 | §2.2 |

## 10. 明确不做的事（当前规模的负收益复杂度）

- MQ 异步下单/削峰 —— 同步链路 + 限流已足够，异步化引入状态查询、消息可靠性等一整套新问题。
- 库存分桶 —— 单行热点在 toB 并发下不是瓶颈。
- 分布式事务框架（Seata 等）—— 本方案用"同步回补 + 幂等 + 对账"的最终一致性即可，成本低一个量级。
