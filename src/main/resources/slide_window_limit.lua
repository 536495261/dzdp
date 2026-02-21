-- 滑动窗口限流 Lua 脚本
-- KEYS[1]: 限流key
-- ARGV[1]: 窗口大小（毫秒）
-- ARGV[2]: 最大请求数
-- ARGV[3]: 当前时间戳（毫秒）
-- ARGV[4]: 请求唯一标识

local key = KEYS[1]
local windowSize = tonumber(ARGV[1])
local maxCount = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requestId = ARGV[4]

-- 1. 删除窗口外的数据
redis.call('zremrangebyscore', key, 0, now - windowSize)

-- 2. 统计窗口内的请求数
local count = redis.call('zcard', key)

-- 3. 判断是否超限
if count >= maxCount then
    return 0  -- 限流
end

-- 4. 添加当前请求
redis.call('zadd', key, now, requestId)

-- 5. 设置过期时间（窗口大小 + 1秒）
redis.call('pexpire', key, windowSize + 1000)

return 1  -- 放行
