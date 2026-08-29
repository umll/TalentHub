# TalentHub 培训课程抢课演示系统

模拟企业内部人力资源系统中的**培训课程限额报名**业务（类秒杀）：员工在限定时间窗口内抢报名额有限的培训课程。系统演示如何用 **Redis 限流 + Lua 原子预扣 + PostgreSQL 条件更新 + 定时对账补偿** 构建一个不超卖、不重复报名、高峰期稳定的报名链路。

- 业务与一致性设计详述：[docs/course-enrollment-design.md](docs/course-enrollment-design.md)
- 工程设计与编码约束：[docs/system-design.md](docs/system-design.md)

## 一、核心设计

**设计原则：任何不一致只允许表现为"少卖"，绝不允许"超卖"或"一人多单"。**

### 1. 两层防线架构

```
用户请求
   │
   ▼
┌─────────────┐  接口限流（Lua 固定窗口：课程维度 QPS + 用户维度防连点）
│   应用层     │  登录态 / 报名时间窗校验
└──────┬──────┘
       ▼
┌─────────────┐  enroll.lua 原子执行三件事：
│    Redis     │  ① 用户去重(SISMEMBER) ② 库存判断 ③ 预扣(DECR+SADD)
└──────┬──────┘  无效请求在此终结，不触达数据库
       ▼ 仅"预扣成功"的请求
┌─────────────┐  单事务：INSERT ON CONFLICT 报名 + 条件 UPDATE 扣库存
│ PostgreSQL  │  行锁 + stock > 0 + 唯一约束 —— 超卖与重复报名的最终防线
└──────┬──────┘
       │ DB 失败 ──► 同步回补 Redis（rollback.lua，幂等）
       ▼
┌─────────────┐
│  对账补偿任务 │  每 30s 核对 Redis 与 DB 库存，以 DB 为准单向修正（纯兜底）
└─────────────┘
```

| 层 | 职责 | 失效后果 |
|---|---|---|
| Redis + Lua | 挡掉重复用户与超量请求，保护 DB | 请求打到 DB，压力上升但**不会超卖** |
| PostgreSQL | 权威数据源，条件更新防超卖，唯一约束防重单 | 无（最后防线） |

### 2. 三个关键机制

**防超卖** —— DB 侧用单条原子条件 UPDATE（不用 `SELECT FOR UPDATE`，锁持有时间减半）：

```sql
UPDATE course SET stock = stock - 1
WHERE id = ? AND stock > 0 AND status = 1;   -- affected = 0 即售罄
```

事务内**先报名 upsert、后扣库存**：热点行锁最后获取、随提交立即释放；并针对 PG 的 MVCC 特性做了 `fillfactor = 70`（促成 HOT 更新）与 autovacuum 调优，缓解热点行高频更新的表膨胀。

**幂等去重（双层）** —— Redis 侧 Lua 脚本把「判重 + 判库存 + 扣减」原子一体，连点/脚本刷不会重复扣库存；DB 侧 `(user_id, course_id)` 唯一约束 + PG 的 `INSERT ... ON CONFLICT` upsert，把"新报名 / 曾取消重新报名 / 重复报名"合并为一条语句。

**一致性保障** —— 预扣成功但 DB 失败时，主路径**同步回补** Redis（回补脚本以 `SREM` 返回值判定资格，天然幂等）；DB 超时这类"结果未知"的场景先反查报名记录再决定是否回补，反查失败宁可少卖不盲补。定时对账任务只做兜底：以 DB 为准，Redis 虚高立即修（防多余请求打 DB），虚低连续 2 轮才修（容忍在途请求），所有修正落 `reconcile_log` 审计表，管理端可见。

### 3. 配套机制

- **库存预热**：开抢前 5 分钟调度任务把 DB 库存与已报名名单加载进 Redis；Lua 对"未预热"明确拒绝（返回 -2），不会把缺失 key 当作 0 或无限。
- **课程生命周期**：调度任务自动完成 未开始→报名中→已结束 的状态翻转。
- **取消回补**：取消方向先 DB 后 Redis，与报名方向（先 Redis 后 DB）一致地保证中间态只可能少卖。

## 二、技术栈与项目结构

| 端 | 技术 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.3 · MyBatis（XML SQL）· PostgreSQL 16 · Spring Data Redis（Lua）· `@Scheduled` |
| 前端 | Vue 3（`<script setup>` + TS strict）· Vite · Arco Design Vue · Pinia · Axios |

```
TalentHub/
├── docs/                       # 设计文档（业务一致性设计 / 工程设计 / UI 提示词）
├── docker-compose.yml          # 本地 PostgreSQL 16 + Redis 7（可选）
├── scripts/bench/              # 并发抢课压测脚本
├── talenthub-server/           # Spring Boot 后端
│   └── src/main/
│       ├── java/com/talenthub/
│       │   ├── controller/     # 参数校验 + 鉴权入口 + 调 service
│       │   ├── service/        # 限流、Redis 库存唯一入口、报名主链路（分支 A/B/C）
│       │   ├── mapper/         # MyBatis 接口（SQL 全部在 resources/mapper/*.xml）
│       │   └── job/            # 生命周期调度 + 对账补偿
│       └── resources/
│           ├── scripts/redis/  # enroll.lua / rollback.lua / rate_limit.lua
│           └── db/             # schema.sql / data.sql（启动自动执行，幂等）
└── talenthub-web/              # Vue 3 前端
    └── src/{api,views,components,composables,stores,types,utils}
```

## 三、如何运行

### 环境要求

- JDK 17+、Maven 3.8+、Node 18+
- PostgreSQL 16 与 Redis：二选一
    - 已有实例（如云服务器）：跳到「启动后端」，用环境变量注入连接信息
    - 本地起：`docker compose up -d`（自带 PG16 + Redis7，账号 `talenthub/talenthub`）

### 启动后端

数据库建好库即可（如 `talenthub`），表结构与演示课程数据随首次启动自动创建（幂等，重复启动安全）。

```bash
cd talenthub-server

# 连接信息通过环境变量注入（均有 localhost 默认值），Windows PowerShell 示例：
#   $env:DB_HOST="你的服务器IP"; $env:DB_NAME="talenthub"; $env:DB_USERNAME="..."; $env:DB_PASSWORD="..."
#   $env:REDIS_HOST="你的服务器IP"; $env:REDIS_PASSWORD="..."
# Linux/macOS 则 export 同名变量

mvn spring-boot:run
```

可用环境变量：`DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD`

- 接口文档（Swagger）：http://localhost:8080/swagger-ui.html （调试需手动带 `X-User-Id` 请求头）

### 启动前端

```bash
cd talenthub-web
npm install
npm run dev
```

访问 http://localhost:5173 ，右上角切换演示用户（「管理员」可见管理端）。开发服务器已配置 `/api` 代理到 `localhost:8080`。

### 并发压测（验证防超卖）

```bash
# 对课程 1 发起 200 个不同用户、50 并发的抢课请求
./scripts/bench/enroll_bench.sh 1 200 50
```

脚本会输出响应码分布（0=成功、41001=名额已满、42900=限流），并给出验证 SQL——预期：**报名数恰好等于总名额，stock 归零，无一超卖**。

## 四、演示剧本

1. **防超卖**：压测后报名数 == 总名额、`stock = 0`、Redis 与 DB 一致。
2. **幂等去重**：同一用户反复点击/并发请求，始终只有一条报名记录。
3. **对账兜底**：手动改坏 Redis 库存（`redis-cli SET course:stock:1 99`），30 秒内对账任务修正，管理端「对账记录」出现 `FIX_REDIS_HIGH` 审计条目。
4. **取消回补**：取消报名后名额立即释放，其他用户可抢；重新报名走 upsert 正常。
5. **预热与生命周期**：新建 3 分钟后开抢的课程，观察倒计时 → 自动预热 → 到点自动开抢。
