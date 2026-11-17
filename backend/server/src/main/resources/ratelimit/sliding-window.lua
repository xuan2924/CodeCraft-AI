-- One bounded hash makes cleanup, global capacity and multi-scope admission atomic,
-- including on Redis Cluster. Values are arrays of individual expiration times.
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local rows = redis.call('HGETALL', KEYS[1])
local buckets = {}
local size = 0
local latest = now
for i = 1, #rows, 2 do
    local active = {}
    for _, expires in ipairs(cjson.decode(rows[i + 1])) do
        if expires > now then
            table.insert(active, expires)
            latest = math.max(latest, expires)
        end
    end
    if #active == 0 then
        redis.call('HDEL', KEYS[1], rows[i])
    else
        buckets[rows[i]] = active
        size = size + 1
        redis.call('HSET', KEYS[1], rows[i], cjson.encode(active))
    end
end
local missing = 0
for i = 3, #ARGV, 3 do
    if not buckets[ARGV[i]] then missing = missing + 1 end
end
if size + missing > capacity then return 0 end
local accepted = 1
for i = 3, #ARGV, 3 do
    local active = buckets[ARGV[i]] or {}
    if #active >= tonumber(ARGV[i + 2]) then
        accepted = 0
    else
        local expires = now + tonumber(ARGV[i + 1])
        table.insert(active, expires)
        latest = math.max(latest, expires)
        redis.call('HSET', KEYS[1], ARGV[i], cjson.encode(active))
    end
end
redis.call('PEXPIRE', KEYS[1], math.max(1, latest - now))
return accepted
