-- 抢课预扣：去重 + 库存判断 + 扣减 原子执行（业务设计 §3.2）
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
