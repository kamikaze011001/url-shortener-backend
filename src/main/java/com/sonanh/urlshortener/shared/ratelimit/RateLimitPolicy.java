package com.sonanh.urlshortener.shared.ratelimit;

import java.time.Duration;

/**
 * One limit: how many requests, over what window, under what name.
 *
 * @param name   appears in the rejection metric and in the Redis key. Changing it resets
 *               every live bucket, which is harmless but worth knowing.
 * @param limit  requests permitted per window.
 * @param window the fixed window length. See {@link RedisRateLimiter} for what "fixed"
 *               costs.
 */
public record RateLimitPolicy(String name, int limit, Duration window) {

	public static RateLimitPolicy perMinute(String name, int limit) {
		return new RateLimitPolicy(name, limit, Duration.ofMinutes(1));
	}

	public static RateLimitPolicy perHour(String name, int limit) {
		return new RateLimitPolicy(name, limit, Duration.ofHours(1));
	}
}
