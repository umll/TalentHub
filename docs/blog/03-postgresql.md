# 从抢课到秒杀（三）：PostgreSQL 防超卖，一条 UPDATE 背后的功夫

> 本文是《从抢课到秒杀》系列的第三篇。上一篇讲了 Redis 预扣层怎么挡掉无效请求，
> 这一篇进入最终防线：数据库。第一篇说过，上层防线全是性能优化，
> **只有这一层是正确性保证**——所以它值得抠到每一个字。

穿过限流和 Redis 预扣的请求，最终要在数据库里完成两件事：插入一条报名记录、扣减一个库存。听起来是 CRUD 入门题，但"绝不超卖 + 一人一单 + 高峰期不拖垮连接池"三个约束叠上来之后，事务里每条语句怎么写、什么顺序写，都有讲究。

## 一、最终防线：条件 UPDATE，而不是 SELECT FOR UPDATE

先看 TalentHub 里防超卖的那条 SQL（`CourseMapper.xml`）：

```sql
UPDATE course
SET stock = stock - 1, updated_at = now()
WHERE id = #{id} AND stock > 0 AND status = 1;
-- affected rows = 1 → 扣减成功；= 0 → 已售罄或状态不符，事务回滚
```

它的正确性论证只需要一句话：**数据库对同一行的写操作天然串行化**。100 个并发事务同时执行这条语句，行锁让它们排队；每个事务拿到锁时看到的都是别人提交后的最新 `stock`，`stock > 0` 不满足就 affected 0。不需要应用层加任何锁，不需要 Redis 配合，不需要重试逻辑——超卖在物理上不可能发生。

很多教程会写成另一种样子：

```sql
-- ⚠️ 不推荐
BEGIN;
SELECT stock FROM course WHERE id = ? FOR UPDATE;   -- 网络往返 1，拿到行锁
-- 应用层判断 stock > 0
UPDATE course SET stock = stock - 1 WHERE id = ?;   -- 网络往返 2
COMMIT;
```

功能等价（`FOR UPDATE` 也能防超卖），但性能差一截，差距全在**锁持有时间**上。热点课程的所有事务都在竞争同一把行锁，系统吞吐上限 ≈ 1 / 单事务锁持有时长。`SELECT FOR UPDATE` 方案从加锁到释放要跨越两次网络往返加一段应用层逻辑；条件 UPDATE 把"判断"塞进了加锁动作本身，锁窗口里只有一次数据页内的比较和写入。锁持有时间减半以上，热点行吞吐直接翻倍。

顺手补一道防御：建表时给 `stock` 加 `CHECK (stock >= 0)` 约束。它在正常路径上永远不会触发——但如果未来有人绕过这条 SQL 直接写库（管理后台、数据订正脚本），CHECK 是最后的最后一道闸。防线思维的精髓就是**假设每一层都可能被绕过**。

## 二、ON CONFLICT：一条语句消灭三个业务分支

"一人一单"的最终保证是唯一约束：

```sql
CONSTRAINT uk_user_course UNIQUE (user_id, course_id)
```

但只有唯一约束是不够优雅的。报名插入其实有三种业务情形：

1. 新用户报名 → `INSERT`
2. 曾取消过、重新报名 → 把旧记录 `UPDATE` 回有效态（取消不删记录，只置 `status = 2`，保留审计轨迹）
3. 重复报名（并发双请求穿过了 Redis 判重的极端窗口）→ 拒绝，但对用户返回"您已报名"的成功语义

朴素实现需要"先查记录状态、再决定 INSERT 还是 UPDATE、还要 catch 唯一约束异常兜底"——三个分支、一次额外查询、一条异常路径，并发下还各有竞态。PostgreSQL 的 `INSERT ... ON CONFLICT` 把这三种情形合并成一条语句（`EnrollmentMapper.xml`）：

```sql
INSERT INTO enrollment (user_id, course_id, status)
VALUES (#{userId}, #{courseId}, 1)
ON CONFLICT (user_id, course_id)
DO UPDATE SET status = 1, enrolled_at = now(), canceled_at = NULL
WHERE enrollment.status = 2;
```

读法：不冲突就插入（情形 1）；冲突且旧记录是"已取消"就复活它（情形 2）；冲突但旧记录仍有效，`WHERE` 不满足，什么都不做（情形 3）。于是 affected rows 只剩两种值、各自只有一种含义：

- `1` → 报名成功（新报或重报，业务上无需区分）
- `0` → 该用户已持有有效报名 → 回滚事务，走"已报名"兜底分支

没有预查询、没有异常路径、并发语义由数据库保证。对比 MySQL 的 `ON DUPLICATE KEY UPDATE`（无法附加 `WHERE` 条件，区分情形 2/3 还得借助表达式技巧），PG 这个语法的表达力是实打实的生产力。

## 三、事务里的次序学：先 upsert，后扣库存

事务里两条语句，谁先谁后？TalentHub 的答案是**先报名 upsert，后扣库存**，这个顺序值得单独一节：

```java
transactionTemplate.executeWithoutResult(tx -> {
    // 顺序必须先 upsert 后扣库存：热点行锁最后获取、随提交即释放
    if (enrollmentMapper.upsertEnrollment(userId, courseId) == 0) {
        throw new BizException(ResultCode.ALREADY_ENROLLED);
    }
    if (courseMapper.deductStock(courseId) == 0) {
        throw new BizException(ResultCode.SOLD_OUT);
    }
});
```

理由有两层：

**① 热点行锁的持有窗口最小化。** 事务里的锁要到提交才释放。`enrollment` 的插入锁的是每人各自的行，无竞争；`course` 那一行是全场唯一的热点。把热点行的 UPDATE 放在事务最后，意味着"拿到热点锁 → 提交释放"之间只隔一次写入——竞争最激烈的资源，持有时间被压到理论最短。反过来先扣库存，热点锁要一直陪跑到 upsert 做完，白白拉长串行段。

**② 无效请求根本不碰热点锁。** 重复报名的请求在第一步 upsert 就返回 0 被拒了，它从头到尾没有竞争过 `course` 行锁。把"淘汰赛"安排在"决赛场"之前，让必然失败的请求败在无竞争的地方。

配套的还有两条事务纪律，写进了工程规范：

- **事务内禁止任何 RPC、Redis 调用、慢查询**——锁窗口里的每一毫秒都在放大串行化代价。TalentHub 的 Redis 回补动作全部安排在事务结束之后（下一篇细讲）。
- **报名接口用独立且收紧的连接池**（HikariCP `maximum-pool-size: 30`）。热点行排队时，请求积压的表现是"在连接池外等"而不是"占着连接在锁上等"——前者限流兜得住，后者会把整个应用的数据库访问拖死。连接池本质上是一种并发限流，这个视角第五篇还会回来。

## 四、PostgreSQL 特有的坑：MVCC 与热点行膨胀

前面的内容换成 MySQL 也大体成立，这一节是 PG 专属的。

PostgreSQL 的 UPDATE 不是原地修改，而是**写一个新行版本，旧版本变成死元组（dead tuple）等 vacuum 回收**。平时无感，但抢课场景里 `course` 那一行会在几秒内被更新几十上百次——每次都留下一具"尸体"：

- 数据页被死元组塞满，行版本链越来越长，读写都要跳过更多垃圾；
- 更隐蔽的是**索引写放大**：默认情况下每个新行版本都要在所有索引里插入新条目，哪怕你只改了 `stock` 这一个没建索引的列。

PG 的解药叫 **HOT（Heap-Only Tuple）更新**：如果新版本能塞进旧版本所在的同一个数据页、且本次更新没碰任何索引列，就不用动索引。为了让 HOT 尽可能命中，TalentHub 做了三件事（都在 `schema.sql`）：

```sql
CREATE TABLE course ( ... ) WITH (fillfactor = 70);
-- 页内预留 30% 空间给新行版本，让 HOT 更新有地方落脚

ALTER TABLE course SET (
    autovacuum_vacuum_scale_factor = 0.01,  -- 死元组超 1% 就触发回收（默认 20%）
    autovacuum_vacuum_cost_delay   = 0      -- 回收不限速
);
```

第三件事是一条纪律而不是配置：**`stock`、`version`、`updated_at` 这些高频变更列上，一个索引都不建。** 只要这些列沾上索引，HOT 直接失效，每次扣库存都变成"堆 + 全部索引"的多点写入。这条纪律最容易被后来者无意破坏——"我想按库存排序，加个索引吧"——所以值得写进 schema 注释里。

顺带回收一个第一篇埋的话头：单行热点的终极方案是**库存分桶**（一行拆 N 行随机扣减），电商单品秒杀的标配。但分桶让"售罄判断"从一次比较变成 N 行聚合、回补与对账复杂度翻倍。TalentHub 到达 DB 的写请求约等于库存量（Redis 层已经把多余请求挡光了），热点根本不成立——**分桶留作演进选项，而不是起手式**。

## 五、小结

| 约束 | 手段 | 关键点 |
|---|---|---|
| 绝不超卖 | 条件 UPDATE（`stock > 0`） | 判断塞进加锁动作，锁窗口最小；CHECK 约束兜底旁路写入 |
| 一人一单 | 唯一约束 + `ON CONFLICT ... WHERE` | 三个业务分支合并成一条语句，affected rows 即语义 |
| 高峰不垮 | 先 upsert 后扣库存 + 短事务 + 独立连接池 | 热点锁最后拿、事务内无外部调用、积压挡在池外 |
| PG 膨胀 | fillfactor 70 + autovacuum 调优 + 热列零索引 | 让 HOT 更新命中，避免索引写放大 |

到这里，正常路径已经闭环：限流放行 → Redis 预扣 → DB 落库，每一层各司其职。但分布式系统的功力从来不在正常路径上——**Redis 扣成功了、数据库却失败了怎么办？失败还分好几种，最刁钻的是"不知道成没成功"。** 下一篇进入全系列技术浓度最高的部分：回补、反查与对账的边界感。

## 参考

- [PostgreSQL 文档：Heap-Only Tuples (HOT)](https://www.postgresql.org/docs/current/storage-hot.html)
- [PostgreSQL 文档：INSERT ... ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)
- [Thoughtworks：秒杀系统架构解析——应对高并发的艺术](https://www.thoughtworks.com/zh-cn/insights/blog/evolutionary-architecture/design-of-high-concurrency)
- [百万流量的秒杀系统架构模型设计（博客园）](https://www.cnblogs.com/yizhiamumu/p/16795196.html)
