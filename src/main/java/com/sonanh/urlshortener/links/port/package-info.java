/**
 * The only part of {@code links} another module may touch.
 *
 * <p>Everything else in this module — use cases, web, store, domain — is private to it,
 * because Modulith exposes a module's base package and treats sub-packages as internal
 * unless they are named like this one. So the folder split is not a convention: moving
 * a type out of {@code port} genuinely removes it from other modules' reach, and the
 * build says so.
 *
 * <p>Depend on this as {@code "links::port"}.
 */
@org.springframework.modulith.NamedInterface("port")
package com.sonanh.urlshortener.links.port;
