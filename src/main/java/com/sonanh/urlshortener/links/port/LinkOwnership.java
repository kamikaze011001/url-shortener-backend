package com.sonanh.urlshortener.links.port;

import java.util.UUID;

/**
 * Answers one question: may this Owner see this Link?
 *
 * <p>Separate from {@link LinkLookup} rather than a method on it. That interface exists
 * to resolve a Short Code for a Redirect, and its return value is deliberately shaped
 * for the hot path; ownership is a different question asked by a different caller, and
 * folding the two together would make every implementation of either carry both.
 *
 * <p><b>The boolean collapses three cases into one on purpose.</b> No such Link, a
 * soft-deleted Link, and someone else's Link all answer {@code false}, so a caller
 * cannot accidentally distinguish them in a response. A 403 for the last case would
 * confirm the Link exists, which is the leak ADR-0008 closes on the redirect path — the
 * management API should not reopen it.
 */
public interface LinkOwnership {

	boolean isOwnedBy(long linkId, UUID ownerId);
}
