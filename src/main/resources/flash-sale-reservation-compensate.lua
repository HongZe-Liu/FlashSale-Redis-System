-- offerId
local offerId = ARGV[1]
-- userId
local userId = ARGV[2]

local stockKey = 'flashsale:stock:' .. offerId
local orderKey = 'flashsale:order:' .. offerId

local removed = redis.call('srem', orderKey, userId)
if (removed == 1) then
    if (redis.call('exists', stockKey) == 1) then
        redis.call('incrby', stockKey, 1)
        return 1
    end
    return 2
end

return 0
