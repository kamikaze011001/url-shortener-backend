package com.sonanh.urlshortener.shared.ratelimit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A fixed-window counter per key, in Redis.
 *
 * <p><b>Why not a token bucket.</b> Bucket4j over Redis is the standard answer and gives
 * smooth refill, and it is the right upgrade. It is not here because this is eleven
 * lines of Lua against a Redis that already exists, with no new dependency to pin, and
 * because the shape of the abstraction — a key, a policy, a verdict — does not change
 * when the algorithm does. Swapping the implementation is this one class.
 *
 * <p><b>What the fixed window costs.</b> A window starts at the first request and ends
 * exactly one window later, so a client that sends its full allowance at 11:59:59 and
 * again at 12:00:01 gets through twice the limit in two seconds. For "5 login attempts a
 * minute" and "10 links a minute" that burst is not the abuse worth engineering against
 * — the limits exist to stop scripted enumeration and floods, both of which are far
 * above this ceiling. A sliding window log or a token bucket removes it, and costs
 * either memory per request or the dependency above.
 *
 * <p><b>Fails open.</b> If Redis is unreachable the request is allowed. A rate limiter
 * that takes the site down when its cache blinks has done more damage than the abuse it
 * prevents, and on the redirect path — where the Visitor is not our user and cannot
 * retry meaningfully — that trade is not close.
 */
@Component
public class RedisRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

	/**
	 * Increment, and set the expiry only on the increment that created the key. Doing
	 * both in one script is what makes this atomic: as two round trips, a process that
	 * died between them would leave a key with no TTL, and that key would reject the
	 * client forever.
	 *
	 * <p>Returns the count and the remaining TTL so the caller can build
	 * {@code Retry-After} without a second query.
	 */
	private static final RedisScript<List> INCREMENT_IN_WINDOW = new DefaultRedisScript<>("""
			local count = redis.call('INCR', KEYS[1])
			if count == 1 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[1])
			end
			return { count, redis.call('PTTL', KEYS[1]) }
			""", List.class);

	private final StringRedisTemplate redis;

	RedisRateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@SuppressWarnings("unchecked")
	public RateLimitVerdict check(String key, RateLimitPolicy policy) {
		String redisKey = "ratelimit:" + policy.name() + ":" + key;

		try {
			List<Long> answer = redis.execute(INCREMENT_IN_WINDOW, List.of(redisKey),
					String.valueOf(policy.window().toMillis()));

			long used = answer.get(0);
			long millisUntilReset = answer.get(1);

			return used > policy.limit()
					? RateLimitVerdict.rejected(policy, millisUntilReset)
					: RateLimitVerdict.allowed(policy, used);
		}
		catch (RuntimeException ex) {
			// Logged at warn, not error: the request succeeded, and an operator watching
			// error rates should not be paged because a limit went unenforced.
			log.warn("ratelimit.unavailable policy={} reason={}", policy.name(), ex.toString());
			return RateLimitVerdict.allowed(policy, 0);
		}
	}
}
