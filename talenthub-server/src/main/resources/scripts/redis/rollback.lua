-- 预扣回补：库存 +1 与移出用户集合 原子成对执行，幂等（业务设计 §3.3）
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
