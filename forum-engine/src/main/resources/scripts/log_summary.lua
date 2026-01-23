-- KEYS[1]: log:counter:taskId (计数器)
-- ARGV[1]: 阈值 (比如 5)
-- ARGV[2]: 过期时间 (秒, 比如 86400)

-- 1. 递增计数器
local count = redis.call('INCR', KEYS[1])

-- 2. 设置过期时间（如果是新创建的计数器）
if count == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end

-- 3. 判断是否达到阈值
if count % tonumber(ARGV[1]) == 0 then
    -- 达到阈值，返回 1 (true)
    return 1
else
    -- 未达到阈值，返回 0 (false) 或 nil
    return nil
end
