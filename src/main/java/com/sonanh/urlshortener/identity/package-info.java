/**
 * Owners: registration, login, and the session.
 *
 * <p>Owns the {@code owners} table. Other modules receive an owner id and never look
 * one up — ownership checks happen where the owned thing lives.
 */
@org.springframework.modulith.ApplicationModule(
		allowedDependencies = { "shared" }
)
package com.sonanh.urlshortener.identity;
