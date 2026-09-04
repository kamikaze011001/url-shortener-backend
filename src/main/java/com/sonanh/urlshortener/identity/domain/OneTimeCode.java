package com.sonanh.urlshortener.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates and hashes the six digits.
 *
 * <p>{@link SecureRandom}, not {@code Math.random()} or {@code Random}. Both of those
 * are seeded predictably enough that an attacker who has watched a few codes can narrow
 * the next one, which turns a million possibilities into far fewer.
 */
public final class OneTimeCode {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int BOUND = 1_000_000;

	private OneTimeCode() {
	}

	/** Zero-padded, so `000042` is a valid code and stays six characters. */
	public static String generate() {
		return String.format("%06d", RANDOM.nextInt(BOUND));
	}

	/**
	 * SHA-256, not bcrypt, and the reasoning does <b>not</b> transfer to passwords.
	 *
	 * <p>bcrypt exists to make brute-forcing a guessable secret slow. A six-digit code
	 * has a million values, so slowness would not save it — what saves it is that it
	 * expires in ten minutes and dies after five attempts. Those bounds do the work, and
	 * a fast hash keeps verification off the request budget.
	 *
	 * <p>What hashing still buys is narrow and worth having: a database leak does not
	 * hand the reader a set of live codes.
	 */
	public static String hash(String code) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}

	/**
	 * Constant-time comparison. The hashes are not secret, so this is belt-and-braces
	 * rather than load-bearing — but a timing-variable compare on a secret-derived value
	 * is the kind of thing that stops being harmless when the code around it changes.
	 */
	public static boolean matches(String code, String storedHash) {
		return MessageDigest.isEqual(
				hash(code).getBytes(StandardCharsets.UTF_8),
				storedHash.getBytes(StandardCharsets.UTF_8));
	}
}
