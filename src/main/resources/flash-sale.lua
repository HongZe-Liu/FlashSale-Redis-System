-- offerId
local offerId = ARGV[1]
-- userId
local userId = ARGV[2]
-- orderId
local id = ARGV[3]

local stockKey = 'flashsale:stock:' .. offerId
local orderKey = 'flashsale:order:' .. offerId
local offerKey = 'flashsale:offer:' .. offerId

-- 1) Validate flash sale time window
local beginTime = tonumber(redis.call('hget', offerKey, 'begin'))
local endTime = tonumber(redis.call('hget', offerKey, 'end'))
local retainSeconds = tonumber(redis.call('hget', offerKey, 'retainSeconds'))
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

-- 2) Validate stock
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    return 1
end

-- 3) Enforce one order per user
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 4) Reserve stock
redis.call('incrby', stockKey, -1)

-- 5) Record user reservation
redis.call('sadd', orderKey, userId)
redis.call('expireat', stockKey, endTime + retainSeconds)
redis.call('expireat', offerKey, endTime + retainSeconds)
redis.call('expireat', orderKey, endTime + retainSeconds)

return 0
