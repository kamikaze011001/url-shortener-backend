package com.sonanh.urlshortener.shared.security;

import java.util.UUID;

/**
 * The authenticated principal.
 *
 * <p>Carries {@code emailVerified} because the filter has already read it — the same
 * lookup that checks the token version, or that resolves an API Key, returns both — so
 * anything downstream gets it without a second query.
 *
 * <p>That is also why verification is not a JWT claim. A claim is fixed when the token
 * is minted, so an Owner who verified two minutes ago would still be refused until they
 * signed out and back in.
 *
 * <p>{@code credential} exists for one rule: FR-8.5 says an API Key may not manage API
 * Keys, so something has to remember <i>how</i> this request authenticated. Without it a
 * leaked key could mint more keys and revoke the real ones.
 */
public record AuthenticatedOwner(UUID id, boolean emailVerified, Credential credential) {

	public enum Credential {
		/** A session cookie. Full authority, including managing API Keys. */
		SESSION,
		/** An API Key. Everything a session can do, except managing API Keys. */
		API_KEY
	}

	public static AuthenticatedOwner viaSession(UUID id, boolean emailVerified) {
		return new AuthenticatedOwner(id, emailVerified, Credential.SESSION);
	}

	public static AuthenticatedOwner viaApiKey(UUID id, boolean emailVerified) {
		return new AuthenticatedOwner(id, emailVerified, Credential.API_KEY);
	}
}
