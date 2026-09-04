package com.sonanh.urlshortener.shared.ratelimit;

/**
 * The answer to one check.
 *
 * @param allowed           whether the request may proceed.
 * @param limit             the policy's ceiling, echoed for the {@code X-RateLimit-Limit}
 *                          header.
 * @param remaining         requests left in this window; never negative, because a
 *                          client being told "-3 remaining" learns nothing useful.
 * @param retryAfterSeconds seconds until the window resets. At least 1: a
 *                          {@code Retry-After: 0} invites an immediate retry that is
 *                          certain to be rejected again.
 */
public record RateLimitVerdict(boolean allowed, int limit, long remaining, long retryAfterSeconds) {

	static RateLimitVerdict allowed(RateLimitPolicy policy, long used) {
		return new RateLimitVerdict(true, policy.limit(), Math.max(0, policy.limit() - used), 0);
	}

	static RateLimitVerdict rejected(RateLimitPolicy policy, long millisUntilReset) {
		return new RateLimitVerdict(false, policy.limit(), 0, Math.max(1, millisUntilReset / 1000));
	}
}
