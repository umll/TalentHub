# 博客系列规划：《从抢课到秒杀：一个 toB 类秒杀系统的设计与演进》

> 以 TalentHub 抢课系统为贯穿案例：1–4 篇讲"系统是什么"（每个断言有仓库真实代码佐证），
> 第 5 篇为衍生纵深篇（组件级主题展开的写法模板），6–8 篇讲"系统往哪演进"，第 9 篇可选。
> 文章存放于 docs/blog/，命名 `NN-slug.md`。

| # | 篇名 | 类型 | 状态 |
|---|---|---|---|
| 1 | 开篇：toB 类秒杀的业务建模与总体架构 | 系统实录 | ✅ 01-overview.md |
| 2 | Redis + Lua：预扣库存的原子化设计 | 系统实录 | ✅ 02-redis-lua.md |
| 3 | PostgreSQL 防超卖：一条 UPDATE 背后的功夫 | 系统实录 | ✅ 03-postgresql.md |
| 4 | 一致性工程：回补、反查与对账的边界感 | 系统实录 | ✅ 04-consistency.md |
| 5 | 服务限流详解：从 30 行 Lua 说开去 | 衍生 | ✅ 05-rate-limiting.md |
| 6 | 同步还是异步？高并发写入的架构决策框架 | 演进 | ✅ 06-sync-vs-async.md |
| 7 | 异步化实战（上）：MQ 削峰的完整链路设计 | 演进 | ✅ 07-mq-async.md |
| 8 | 异步化实战（下）：让 MQ 跑在你想要的 QPS/TPS 上 | 演进 | ✅ 08-mq-tuning.md |
| 9 | 彩蛋：如何证明"不超卖"——压测、验证与监控 | 可选 | |

## 各篇核心内容速记

1. **开篇**：抢课业务建模；与电商秒杀的差异（量级/少卖可容忍/无黑产/要即时结果）；设计原则"任何不一致只允许表现为少卖"；两层防线架构与失效分析；不做 MQ/分桶/分布式事务的理由；系列导读。
2. **Redis+Lua**：GET+DECR 的竞态；enroll.lua 三合一原子（去重/判断/扣减）与未预热防御；rollback.lua 以 SREM 判资格的幂等回补；限流 fail-open 立场；预热生命周期。
3. **PG 防超卖**：条件 UPDATE vs SELECT FOR UPDATE；ON CONFLICT upsert 三合一；先 upsert 后扣库存的锁次序；MVCC 热点行膨胀（fillfactor/HOT/autovacuum）；唯一约束兜底。
4. **一致性工程**：不一致窗口与方向纪律；DB 失败分支 A/B/C（重点：结果未知先反查、反查失败宁少卖不盲补）；对账任务定位（兜底/DB 为准/单向修正/虚低两轮才修/审计日志）。
5. **限流详解（衍生）**：系统两级 Lua 限流引子与 fail-open 立场；四大算法（固定/滑动窗口、漏桶、令牌桶）图解对比；单机工具（Guava/Bucket4j/Sentinel）；分布式（Redis+Lua/Redisson/网关）；限流对象分层、拒绝策略、阈值设定；选型速查表。
6. **同步 vs 异步**：单行热点 TPS 上限估算；两种架构优缺点全表；决策清单（峰值 QPS/库存量级/时效要求/运维能力）；TalentHub 选同步的论证。
7. **MQ 削峰（上）**：预扣成功→投消息→匀速消费落库的改造方案；堆积/匀速/至少一次三特性；幂等消费、消息可靠性、状态查询接口、超时回补；半异步折中。
8. **MQ 调速（下）**：目标 TPS = 单条耗时 × 并发度反推；RocketMQ 三板斧（线程数/拉取节奏/令牌桶）；Kafka 分区与 max.poll.records；生产端 batch/linger 权衡；积压治理；参数速查表。
9. **压测验证**：bench 脚本、验证 SQL、故意改坏 Redis 演示对账、监控告警清单。

## 参考文章总表

- 阿里云：使用 Redis 构建秒杀业务系统 https://help.aliyun.com/zh/redis/use-cases/use-apsaradb-for-redis-to-build-a-business-system-that-can-handle-flash-sales
- JavaGuide 服务限流详解 https://javaguide.cn/high-availability/limit-request.html
- Thoughtworks 秒杀系统架构解析 https://www.thoughtworks.com/zh-cn/insights/blog/evolutionary-architecture/design-of-high-concurrency
- 美团技术团队高并发专题 https://tech.meituan.com/tags/高并发.html
- 基于 Redis+Lua 的库存减扣方案（知乎） https://zhuanlan.zhihu.com/p/659373049
- Redis+Lua 解决高并发抢购（博客园） https://www.cnblogs.com/itbsl/p/15021263.html
- RocketMQ 削峰实战（阿里云） https://developer.aliyun.com/article/757223
- RocketMq 异步、解耦、削峰（知乎） https://zhuanlan.zhihu.com/p/668020790
- 秒杀订单异步化架构（CSDN） https://blog.csdn.net/weixin_42405670/article/details/118138802
- RocketMQ 流控实战（CSDN） https://blog.csdn.net/qq_29978863/article/details/107604184
- RocketMQ 消费者最佳实践（博客园） https://www.cnblogs.com/zuoyang/p/14436103.html
- RocketMQ 调优心得（CSDN） https://blog.csdn.net/qq_27641935/article/details/106015319
- Kafka 线上性能优化实战 https://monchickey.com/post/2024/04/18/kafka-performance-optimization/
- 4 种经典限流算法图文详解（阿里云） https://developer.aliyun.com/article/1705177
- 基于 Redis 和 Lua 的分布式限流（阿里云） https://developer.aliyun.com/article/697056
- Sentinel vs Resilience4j vs Bucket4j（CSDN） https://blog.csdn.net/qq_35716689/article/details/151099108
- Spring Cloud Gateway 全链路限流对比（博客园） https://www.cnblogs.com/yxysuanfa/p/19097345
