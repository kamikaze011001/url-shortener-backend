package com.sonanh.urlshortener.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints and hashes API Keys.
 *
 * <p>Format is {@code sk_live_} plus 32 random bytes, URL-safe base64 without padding.
 * The prefix is not decoration: a key that announces what it is can be recognised by a
 * secret scanner in a repository or a log, which is the difference between a leak that
 * is noticed and one that is not. It also stops an Owner pasting the wrong string into a
 * config file and getting an unexplained 401.
 */
public final class ApiKeys {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String PREFIX = "sk_live_";
	private static final int ENTROPY_BYTES = 32;

	/** Enough of the key to recognise it in a list; nowhere near enough to use it. */
	private static final int DISPLAY_PREFIX_LENGTH = 12;

	private ApiKeys() {
	}

	public static String generate() {
		byte[] bytes = new byte[ENTROPY_BYTES];
		RANDOM.nextBytes(bytes);
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * SHA-256, not bcrypt, and for a reason that does <b>not</b> transfer to passwords.
	 *
	 * <p>Two arguments, and the first is the one that decides it. <b>bcrypt cannot be
	 * indexed</b> — its salt is per row, so verifying a key would mean loading candidate
	 * rows and comparing them one at a time, with no way to find the row first. A plain
	 * hash makes the stored value itself the lookup key, so authentication is a single
	 * indexed read.
	 *
	 * <p>The second: bcrypt is slow on purpose, to punish brute-forcing a <i>guessable</i>
	 * secret. This key is 256 bits from a CSPRNG. There is no dictionary to walk, and no
	 * amount of slowness is what protects it.
	 *
	 * <p>Neither argument applies to a password, which a human chose and which almost
	 * certainly appears in a wordlist. If this comment is ever cited to justify a fast
	 * password hash, it has been misread.
	 */
	public static String hash(String key) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}

	public static String displayPrefix(String key) {
		return key.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, key.length()));
	}

	public static String last4(String key) {
		return key.substring(key.length() - 4);
	}

	/** Cheap reject before touching the database, for anything that is not one of ours. */
	public static boolean looksLikeKey(String value) {
		return value != null && value.startsWith(PREFIX) && value.length() > PREFIX.length() + 16;
	}
}
