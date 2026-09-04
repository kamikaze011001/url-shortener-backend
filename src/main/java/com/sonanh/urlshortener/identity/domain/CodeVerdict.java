package com.sonanh.urlshortener.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a submitted code is good, and says why when it is not.
 *
 * <p>Pure: it is handed the stored facts and returns a verdict. The caller does the
 * reading, the attempt counting and the consuming, which keeps every database write in
 * one place and leaves this class trivially testable with no fixtures.
 *
 * <p>The two limits live here rather than in a use case because they are what make a
 * six-digit secret defensible at all — a million possibilities is nothing without them,
 * and separating the numbers from the reasoning is how one of them quietly changes.
 */
public final class CodeVerdict {

	/**
	 * Long enough to survive a slow mail relay, short enough that an intercepted mailbox
	 * is a narrow window. It is a guess, and the number most likely to want revisiting
	 * once real delivery times are known.
	 */
	public static final Duration TTL = Duration.ofMinutes(10);

	/** Five wrong guesses against a million values is hopeless. Unlimited is patience. */
	public static final short MAX_ATTEMPTS = 5;

	private CodeVerdict() {
	}

	public enum Result {
		OK,
		/** Wrong digits, and attempts remain. */
		WRONG,
		/** Past its life. The fix is to request another, not to guess again. */
		EXPIRED,
		/** Too many wrong guesses. Reported as invalid, never as "you are close". */
		EXHAUSTED
	}

	public static Result check(String storedHash, short attempts, Instant expiresAt,
			String submitted, Instant now) {

		// Attempts before expiry: a code that has been guessed at five times is burnt
		// whether or not it has also expired, and saying "expired" would invite a
		// resend that hands the guesser a fresh target.
		if (attempts >= MAX_ATTEMPTS) {
			return Result.EXHAUSTED;
		}
		if (!expiresAt.isAfter(now)) {
			return Result.EXPIRED;
		}
		return OneTimeCode.matches(submitted, storedHash) ? Result.OK : Result.WRONG;
	}
}
