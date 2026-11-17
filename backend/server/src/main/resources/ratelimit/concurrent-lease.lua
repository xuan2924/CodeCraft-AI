-- KEYS[1]: bounded global terminal hash. ARGV: operation, task, owner token, capacity, lease milliseconds.
-- Redis TIME avoids Java node clock skew. Release/renew never revive an expired or replaced owner.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local rows = redis.call('HGETALL', KEYS[1])
local current = nil
local count = 0
local latest = now
for i = 1, #rows, 2 do
    local lease = cjson.decode(rows[i + 1])
    if lease.expires <= now then
        redis.call('HDEL', KEYS[1], rows[i])
    else
        count = count + 1
        latest = math.max(latest, lease.expires)
        if rows[i] == ARGV[2] then current = lease end
    end
end
local owned = current and current.token == ARGV[3]
if ARGV[1] == 'release' then
    if owned then redis.call('HDEL', KEYS[1], ARGV[2]); return 1 end
    return 0
end
if ARGV[1] == 'renew' then
    if not owned then return 0 end
elseif ARGV[1] == 'acquire' then
    if current or count >= tonumber(ARGV[4]) then return 0 end
else
    return redis.error_reply('invalid lease operation')
end
local expires = now + tonumber(ARGV[5])
redis.call('HSET', KEYS[1], ARGV[2], cjson.encode({token = ARGV[3], expires = expires}))
redis.call('PEXPIRE', KEYS[1], math.max(latest, expires) - now)
return 1
