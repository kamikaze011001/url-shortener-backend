package com.sonanh.urlshortener.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns a presented API Key into an Owner, or nothing.
 *
 * <p>Inverted for the same reason as {@link OwnerAuthState}: the filter that reads the
 * header lives in {@code shared}, the {@code api_keys} table belongs to
 * {@code identity}, and {@code identity} already depends on {@code shared}. So
 * {@code shared} declares the question and {@code identity} answers it.
 */
public interface ApiKeyAuthenticator {

	/**
	 * @param presentedKey the raw value from the {@code Authorization} header.
	 * @return empty for an unknown, malformed or revoked key — all three are the same
	 *         answer to the caller, and distinguishing them would tell a prober which of
	 *         their guesses was once real.
	 */
	Optional<Authenticated> authenticate(String presentedKey);

	record Authenticated(UUID ownerId, boolean emailVerified, long keyId) {}
}
