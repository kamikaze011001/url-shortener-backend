/**
 * The only part of {@code analytics} another module may touch.
 *
 * <p>Holds {@link com.sonanh.urlshortener.analytics.port.ClickRecorder}, the seam from
 * ADR-0005. Depend on this as {@code "analytics::port"}.
 */
@org.springframework.modulith.NamedInterface("port")
package com.sonanh.urlshortener.analytics.port;
