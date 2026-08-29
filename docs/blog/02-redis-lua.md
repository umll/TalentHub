# 从抢课到秒杀（二）：Redis + Lua，预扣库存的原子化设计

> 本文是《从抢课到秒杀》系列的第二篇。上一篇讲了两层防线的总体架构，
> 这一篇拆解第一道防线：Redis 预扣层。全部代码来自 TalentHub 仓库的真实实现。

上一篇说过，Redis 层的职责是**把 99% 的无效请求挡在数据库之外**——重复报名的、库存已尽的，都不该去碰那条宝贵的数据库行锁。这一篇回答三个问题：

1. 为什么朴素写法都是错的？
2. 预扣脚本怎么写才对？
3. 比预扣更难的其实是"扣错了怎么还回去"。

## 一、先看两种典型的错误写法

### 错误一：先查后扣（check-then-act 竞态）

最直觉的写法，Java 伪代码：

```java
// ❌ 错误示范
Long stock = redis.get("course:stock:1");
if (stock > 0) {
    redis.decr("course:stock:1");   // 扣减
    // ... 去数据库下单
}
```

`GET` 和 `DECR` 是两条独立命令，之间存在竞态窗口。库存剩 1 时，100 个并发请求同时 `GET` 到 1、同时通过判断、同时 `DECR`——库存直接被扣成 -99，放行了 100 个请求打向数据库。Redis 单线程保证的是**单条命令**的原子性，不是**你的业务逻辑**的原子性。

### 错误二：只靠 DECR 判负

有经验一点的写法，利用 `DECR` 的返回值：

```java
// ⚠️ 依然不够
Long after = redis.decr("course:stock:1");
if (after < 0) {
    redis.incr("course:stock:1");   // 扣穿了，还回去
    return "已抢完";
}
```

这解决了超扣，但还有三个洞：

1. **没有去重**。同一个用户连点 10 次（或写个脚本刷），会扣掉 10 个库存名额。后面数据库的唯一约束会拦住重复报名，但 Redis 库存已经白白少了 9 个——表现为**少卖**，热门课程提前显示"已抢完"。
2. **判断与去重无法原子组合**。就算加一个 `SISMEMBER` 判重，它和 `DECR` 之间又出现了新的竞态窗口，回到错误一。
3. **key 不存在时行为未定义**。`DECR` 一个不存在的 key，Redis 会把它当 0 开始减——一门还没"预热"库存的课程，会从 0 被扣成负数，语义完全失控。

结论：**去重、判断、扣减这三件事，必须在一个原子单元里完成。** 这正是 Lua 脚本的用武之地——Redis 把整个脚本作为一个整体执行，执行期间不会插入任何其他命令。

## 二、enroll.lua：三十行内解决战斗

TalentHub 的预扣脚本全文（`resources/scripts/redis/enroll.lua`）：

```lua
-- 抢课预扣：去重 + 库存判断 + 扣减 原子执行
-- KEYS[1] = course:stock:{courseId}   （String，剩余库存）
-- KEYS[2] = course:users:{courseId}   （Set，已预扣用户集合）
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

几处值得展开的设计：

**① `EXISTS` 前置判断——"未预热"是一等公民状态。**
这是最容易被省掉、也最不该省的四行。库存 key 有两种缺失场景：活动还没预热，或 key 意外过期/被清。无论哪种，正确行为都是**明确拒绝并触发告警**，而不是让 `DECR` 从 0 开始减出一个负数世界。对应第一篇的原则：拒绝 = 少卖（可修复），放行 = 潜在超卖（不可接受）。

**② 判重在判库存之前。**
重复请求是常态（用户连点、前端重试），库存耗尽后判重照样要正确返回 -1 而不是 0——用户已经抢到了，即使课程后来卖光，也应该告诉他"您已报名"而非"名额已满"。顺序反了，语义就错了。

**③ 所有写操作放在所有判断之后。**
脚本前半段只读，后半段只写。这让脚本不存在"部分成功"的中间态——要么一个写都没发生（返回 0/-1/-2），要么 `DECR` 和 `SADD` 成对生效（返回 1）。Lua 脚本出错时 Redis 并不会回滚已执行的命令，所以"读写分段"是比"依赖原子性"更稳的写法习惯。

**④ 返回值是协议，不是布尔。**
四个返回码各自对应不同的业务语义与用户话术（成功 / 已满 / 已报名 / 未开放）。Java 侧会把它们转成枚举（见第四节），业务代码里不允许出现裸的 `-1`。

## 三、rollback.lua：比扣减更难的是回补

预扣成功后，请求带着"入场券"去数据库落库。但数据库可能失败——此时 Redis 里那个被扣掉的名额必须还回去，否则就是永久少卖。

回补看似一个 `INCR` 的事，实际上有个隐蔽的坑：**回补必须幂等**。网络抖动下回补请求会重试；对账任务和主路径可能同时尝试修复同一笔差异。如果回补是裸 `INCR`，重试两次就把库存多加了一个——库存虚高，放行多余请求打穿到数据库（虽然最终防线还是拦得住超卖，但这是给自己的一致性挖坑）。

TalentHub 的解法是让**用户集合成为回补的资格凭证**（`rollback.lua`）：

```lua
-- 预扣回补：库存 +1 与移出用户集合 原子成对执行，幂等
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

精髓在 `SREM` 的返回值上：一个用户在集合里只可能存在一次，`SREM` 天然只会成功一次。第一次回补移除成功、库存 +1；之后不管重试多少次，`SREM` 都返回 0，`INCR` 不会执行。**幂等不是靠调用方小心，而是靠数据结构本身的性质**——这类"把不变量下沉到存储语义"的手法，比在应用层加分布式锁优雅得多。

预扣与回补合起来还维护着一个隐含的不变量：

> `course:stock` 的扣减数 == `course:users` 的成员数（都等于"持有预扣的用户数"）

两个脚本都是对这两个结构的**原子成对操作**，不变量在任何时刻都成立。第四篇讲对账任务时，这个不变量就是差异检测的依据。

## 四、Java 侧：脚本是资产，返回码要翻译

两个工程习惯，比脚本本身更影响长期可维护性。

**脚本随代码仓库管理，随应用启动加载。** 不要人肉在 redis-cli 里 `SCRIPT LOAD` 再把 SHA 硬编码进配置——脚本一改，SHA 就成了定时炸弹。Spring Data Redis 的 `DefaultRedisScript` 会自动处理 EVALSHA/EVAL 的降级：

```java
@Bean
public DefaultRedisScript<Long> enrollScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(
            new ClassPathResource("scripts/redis/enroll.lua")));
    script.setResultType(Long.class);
    return script;
}
```

**返回码在唯一入口处翻译成语义化枚举。** TalentHub 把所有 Redis 库存操作收敛进一个 `StockCacheService`，Lua 返回码在这里完成翻译，业务代码只见枚举不见数字：

```java
return switch (result.intValue()) {
    case 1  -> PreDeductResult.PRE_DEDUCTED;
    case 0  -> PreDeductResult.SOLD_OUT;
    case -1 -> PreDeductResult.DUPLICATE;
    case -2 -> PreDeductResult.NOT_PREHEATED;
    default -> throw new BizException(ResultCode.SYSTEM_BUSY);
};
```

这层收敛的价值在系统演进时体现：以后要换库存数据结构、加监控埋点、做分桶改造，改动都被锁在一个类里。

## 五、预热：让 key "存在"成为一个受管理的状态

enroll.lua 对"key 不存在"返回 -2，对应的运维侧设计就是**库存预热**——活动开始前把 DB 库存与已报名名单加载进 Redis：

```
开抢前 5 分钟：调度任务扫描候选课程
  → SET course:stock:{id} = DB 库存
  → 重建 course:users:{id} = DB 有效报名用户集合
  → 两个 key 统一设置过期时间 = 报名截止 + 7 天
```

三个细节：

- **预热以 DB 为准、全量重建**（先 `DEL` 再 `SADD`），它同时也是对账任务修复差异时的复用路径——"预热"和"修复"本质是同一个操作：让 Redis 回到 DB 的投影。
- **过期时间统一设在截止后 7 天**，留给对账与查询窗口，且两个 key 同生共死，避免"库存在、集合没了"的半状态。
- **预热失败要有兜底**：调度任务每轮检查"已开抢但缺 key"的课程并补预热 + 告警；即便兜底也失败，enroll.lua 的 -2 保证系统的最坏行为是"拒绝报名"而不是"行为未定义"。

## 六、还有一个 Lua：限流，以及 fail-open 的立场

抢课接口前面还挂着两级限流（课程维度 QPS + 用户维度防连点），也是 Lua 固定窗口实现——但这里只提一个与本篇主题呼应的设计立场：

```java
} catch (Exception e) {
    // 限流是保护手段而非正确性手段：Redis 异常时放行，由 DB 最终防线兜底
    log.warn("限流脚本执行失败，本次放行: key={}", key, e);
    return;
}
```

限流脚本执行失败时，选择 **fail-open（放行）**。因为在这套架构里限流只是性能保护，正确性由数据库那条条件 UPDATE 兜底——限流挂了，最坏是 DB 累一点。反过来，预扣脚本失败时则是 **fail-closed（拒绝）**：预扣管的是名额语义，宁可少卖。

**同样是"Redis 出错怎么办"，答案取决于这段逻辑守护的是性能还是正确性。** 这是比任何具体算法都值得带走的判断框架。限流本身值得单独一篇——四大算法、单机与分布式方案、阈值怎么定，留到本系列第五篇展开。

## 七、小结

| 问题 | 答案 |
|---|---|
| 为什么必须用 Lua | 去重/判断/扣减三件事要原子，单条命令与命令组合都做不到 |
| key 不存在怎么办 | 一等公民状态：明确拒绝（-2）+ 告警，绝不当 0 或无限 |
| 回补怎么做到幂等 | 以 `SREM` 返回值判资格，把幂等下沉到 Set 的数据结构性质里 |
| 脚本怎么管理 | 进代码仓库、启动时加载；返回码在唯一入口翻译成枚举 |
| Redis 出错怎么办 | 守正确性的 fail-closed，守性能的 fail-open |

预扣成功的请求，接下来要去闯真正的最终防线——数据库。下一篇讲 PostgreSQL 侧的功夫：为什么一条条件 UPDATE 比 `SELECT FOR UPDATE` 强、`ON CONFLICT` 怎么把三个业务分支合并成一条语句，以及 MVCC 给热点行埋的坑。

## 参考

- [阿里云：使用 Redis 构建秒杀业务系统](https://help.aliyun.com/zh/redis/use-cases/use-apsaradb-for-redis-to-build-a-business-system-that-can-handle-flash-sales)
- [基于 Redis+Lua 脚本的库存减扣方案（知乎）](https://zhuanlan.zhihu.com/p/659373049)
- [Redis+Lua 解决高并发场景抢购秒杀问题（博客园）](https://www.cnblogs.com/itbsl/p/15021263.html)
- [高并发秒杀系统实战：Redis+Lua 防超卖与库存扣减优化（腾讯云开发者社区）](https://cloud.tencent.com/developer/article/2540700)
