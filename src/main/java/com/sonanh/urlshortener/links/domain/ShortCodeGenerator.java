package com.sonanh.urlshortener.links.domain;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * ADR-0002: 7 random base62 characters from {@link SecureRandom}.
 *
 * <p>Not a monotonic counter, which would be enumerable — anyone could walk /1, /2, /3
 * and harvest every Link, and the code length would leak how many exist.
 *
 * <p>Nothing here checks availability. The unique index on {@code links.code} is the
 * collision detector; the caller inserts and retries. Checking first would be both
 * slower and racy.
 *
 * <p>At 10^8 links against 62^7 ≈ 3.5 × 10^12 codes the fill ratio is 0.003%, so a
 * collision is rare and three consecutive collisions is effectively impossible.
 */
@Component
public class ShortCodeGenerator {

	static final int CODE_LENGTH = 7;

	private static final char[] ALPHABET =
			"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

	private final SecureRandom random = new SecureRandom();

	public String generate() {
		char[] out = new char[CODE_LENGTH];
		for (int i = 0; i < CODE_LENGTH; i++) {
			out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
		}
		return new String(out);
	}
}
