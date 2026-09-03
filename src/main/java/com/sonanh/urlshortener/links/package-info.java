/**
 * Owns the Link: creation, editing, disabling, deletion, and listing.
 *
 * <p>This module owns the {@code links} table. No other module queries it — the
 * {@link com.sonanh.urlshortener.links.LinkLookup} port is the only way in, so that a
 * later extraction of {@code redirect} turns a method call into a network call rather
 * than a rewrite.
 *
 * @see <a href="../../../../../../contracts/openapi.yaml">the contract</a>
 */
@org.springframework.modulith.ApplicationModule(
		allowedDependencies = { "shared" }
)
package com.sonanh.urlshortener.links;
