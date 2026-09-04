package com.sonanh.urlshortener.shared.security;

import java.util.Optional;
import java.util.UUID;

/**
 * The two facts every authenticated request needs about an Owner, and nothing else.
 *
 * <p><b>This interface exists to invert a dependency.</b> The auth filter lives here in
 * {@code shared}, but both facts come from the {@code owners} table, which
 * {@code identity} owns. {@code identity} already depends on {@code shared}, so having
 * {@code shared} reach back would be a cycle and Modulith would fail the build.
 *
 * <p>So {@code shared} declares what it needs and {@code identity} supplies it. The
 * arrow still points one way at compile time; only the call goes the other. That is the
 * whole trick, and it is why this file is an interface with two fields rather than a
 * repository.
 *
 * <p>Kept deliberately narrow. Widening it to return an Owner would let the security
 * layer read email addresses and password hashes it has no business seeing, and would
 * make every future column a security-layer concern.
 */
public interface OwnerAuthState {

	/**
	 * @return empty when no such Owner exists — which is not an error. A token can
	 *         outlive the account it names, and that request is simply unauthenticated.
	 */
	Optional<State> find(UUID ownerId);

	/**
	 * Called when either fact changes, so the next request sees it immediately rather
	 * than after a cache expiry. Revocation that takes effect "within five minutes" is
	 * not revocation.
	 */
	void invalidate(UUID ownerId);

	/**
	 * @param tokenVersion the value a session token must carry to still be valid. A
	 *                     password reset increments it, and every token issued before
	 *                     that instant stops matching (ADR-0018).
	 */
	record State(int tokenVersion, boolean emailVerified) {}
}
