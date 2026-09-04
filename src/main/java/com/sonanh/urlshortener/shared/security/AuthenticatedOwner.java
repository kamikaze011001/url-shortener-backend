package com.sonanh.urlshortener.shared.security;

import java.util.UUID;

/**
 * The authenticated principal.
 *
 * <p>Was a bare {@link UUID}. It carries {@code emailVerified} now because the filter
 * has already read that fact — the same lookup that checks the token version returns
 * both — so anything downstream that needs it gets it for free rather than issuing a
 * second query.
 *
 * <p>That is also why verification status is not a JWT claim. A claim is fixed when the
 * token is minted, so an Owner who verified two minutes ago would still be refused
 * until they signed out and back in. Reading it per request costs nothing extra here
 * and cannot go stale.
 */
public record AuthenticatedOwner(UUID id, boolean emailVerified) {
}
