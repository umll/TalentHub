-- 固定窗口限流（业务设计 §7）
-- KEYS[1] = 计数 key
-- ARGV[1] = 窗口秒数
-- ARGV[2] = 窗口内上限
-- 返回值: 1=放行  0=超限

local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
if count > tonumber(ARGV[2]) then
    return 0
end
return 1
