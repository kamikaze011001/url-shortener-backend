/**
 * Cross-cutting building blocks: the error model, identifiers, clock, and client IP
 * resolution.
 *
 * <p>Declared {@code OPEN} so other modules may use its types freely. This is the one
 * module without an {@code internal} package, deliberately: everything here is API.
 *
 * <p>Nothing with business behaviour belongs here. A type that only {@code links} uses
 * belongs in {@code links}, not in {@code shared}.
 */
@org.springframework.modulith.ApplicationModule(
		type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.sonanh.urlshortener.shared;
