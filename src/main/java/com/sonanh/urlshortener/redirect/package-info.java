/**
 * The hot path: resolving a Short Code and answering a Visitor.
 *
 * <p>Served on the short host only, where the entire root path namespace belongs to
 * Short Codes (ADR-0006).
 *
 * <p><b>On the declared dependencies.</b> They name <em>named interfaces</em>
 * ({@code links::port}), not whole modules, so this module can reach the ports and
 * nothing else. Depending on {@code "links"} would have left it free to call
 * {@code CreateLinkUseCase}; naming the port closes that.
 *
 * <p> An early draft of the architecture claimed
 * this module depended on nothing but {@code shared}. That was only achievable by
 * querying the {@code links} table directly, which trades a visible code dependency
 * for an invisible data one — worse, not better. It goes through
 * {@link com.sonanh.urlshortener.links.LinkLookup} and
 * {@link com.sonanh.urlshortener.analytics.ClickRecorder}, both narrow ports that
 * become network calls if this module is ever extracted.
 */
@org.springframework.modulith.ApplicationModule(
		allowedDependencies = { "links::port", "analytics::port", "shared" }
)
package com.sonanh.urlshortener.redirect;
