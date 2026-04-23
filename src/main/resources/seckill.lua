-- voucherId
local voucherId = ARGV[1]
-- userId
local userId = ARGV[2]
-- orderId（你这里叫 id）
local id = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1) 校验库存
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    return 1
end

-- 2) 校验一人一单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 3) 扣库存
redis.call('incrby', stockKey, -1)

-- 4) 记录已下单
redis.call('sadd', orderKey, userId)

-- 5) 写入 Stream（消息队列）
redis.call('xadd', 'stream.orders', '*',
    'userId', userId,
    'voucherId', voucherId,
    'id', id
)

return 0
