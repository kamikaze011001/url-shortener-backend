/**
 * Recording Clicks and reading statistics.
 *
 * <p>Depends on {@code links} only to check that the caller owns the Link whose
 * statistics they are asking for.
 */
@org.springframework.modulith.ApplicationModule(
		allowedDependencies = { "links", "shared" }
)
package com.sonanh.urlshortener.analytics;
