-- One token bucket, checked and taken in one step. Redis runs a script while no other command
-- runs, so two gateway instances can never both take the last token of the same bucket.
-- The arithmetic is TokenBucket.java's, line for line: one token is period_ms credits, and every
-- elapsed millisecond adds refill_tokens credits, so every value stays a whole number.
--
-- KEYS[1]  the bucket: a hash with the fields credits (fill level) and refilled_ms (last refill)
-- ARGV[1]  capacity, in tokens (the burst size)
-- ARGV[2]  tokens added per refill period
-- ARGV[3]  the refill period, in milliseconds
-- Returns  { allowed (1 or 0), whole tokens left, Retry-After in milliseconds (0 when allowed) }

local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local period_ms = tonumber(ARGV[3])

-- Redis's own clock, not the gateway's: three instances whose clocks disagree still share one
-- timeline. TIME is allowed in a script because Redis replicates the script's writes, not the
-- script itself (the only mode since Redis 7.0).
local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local credits_per_token = period_ms
local capacity_credits = capacity * credits_per_token

-- A bucket that does not exist yet is full: a new caller starts with the whole burst.
local state = redis.call('HMGET', KEYS[1], 'credits', 'refilled_ms')
local credits = tonumber(state[1]) or capacity_credits
local refilled_ms = tonumber(state[2]) or now_ms

-- A clock that steps back (a failover to a replica whose clock is behind) adds nothing.
local elapsed_ms = math.max(0, now_ms - refilled_ms)
credits = math.min(capacity_credits, credits + elapsed_ms * refill_tokens)
refilled_ms = math.max(refilled_ms, now_ms)

local allowed = 0
local retry_after_ms = 0
if credits >= credits_per_token then
  credits = credits - credits_per_token
  allowed = 1
else
  retry_after_ms = math.ceil((credits_per_token - credits) / refill_tokens)
end

redis.call('HSET', KEYS[1], 'credits', credits, 'refilled_ms', refilled_ms)
-- Left alone, the bucket is full again after this long; from then on it says nothing a new
-- bucket would not, so Redis may forget it. Idle callers cost no memory.
redis.call('PEXPIRE', KEYS[1], math.ceil((capacity_credits - credits) / refill_tokens) + 1)

-- Lua numbers become integer replies; every value here is already whole.
return { allowed, math.floor(credits / credits_per_token), retry_after_ms }
