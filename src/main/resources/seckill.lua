-- voucherId
local voucherId = ARGV[1]
-- userId
local userId = ARGV[2]
-- orderId（你这里叫 id）
local id = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local voucherKey = 'seckill:voucher:' .. voucherId

-- 1) 校验活动时间
local beginTime = tonumber(redis.call('hget', voucherKey, 'begin'))
local endTime = tonumber(redis.call('hget', voucherKey, 'end'))
local retainSeconds = tonumber(redis.call('hget', voucherKey, 'retainSeconds'))
if (beginTime == nil or endTime == nil) then
    return 5
end
if (retainSeconds == nil) then
    retainSeconds = 86400
end

local now = tonumber(redis.call('time')[1])
if (now < beginTime) then
    return 3
end
if (now > endTime) then
    return 4
end

-- 2) 校验库存
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    return 1
end

-- 3) 校验一人一单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 4) 扣库存
redis.call('incrby', stockKey, -1)

-- 5) 记录已下单
redis.call('sadd', orderKey, userId)
redis.call('expireat', stockKey, endTime + retainSeconds)
redis.call('expireat', voucherKey, endTime + retainSeconds)
redis.call('expireat', orderKey, endTime + retainSeconds)

-- 6) 写入 Stream（消息队列）
redis.call('xadd', 'stream.orders', '*',
    'userId', userId,
    'voucherId', voucherId,
    'id', id
)

return 0
