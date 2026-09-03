package com.sonanh.urlshortener.links.domain;

import java.net.URI;

/**
 * Decides whether a proposed Destination may be shortened at all — separate from
 * checking that it parses as a URL.
 *
 * <p>ADR-0010: one implementation today, {@link LocalRulesScreener}, which needs no
 * external service and can never be unavailable. External reputation screening (Google
 * Safe Browsing or equivalent) is a second implementation of this interface, deferred
 * because it introduces a latency and availability dependency on the create path and
 * forces a fail-open-or-fail-closed decision that needs background re-screening to
 * answer well.
 *
 * <p>This interface is the whole point of that deferral: adding screening later is a
 * new implementation and a configuration flag, not a change to the creation flow.
 */
public interface DestinationScreener {

	Verdict screen(URI destination);

	/**
	 * @param reason why it was refused, for the caller to map to an error code and for
	 *               the log. Null when allowed.
	 */
	record Verdict(boolean allowed, Refusal reason) {

		public static Verdict allow() {
			return new Verdict(true, null);
		}

		public static Verdict refuse(Refusal reason) {
			return new Verdict(false, reason);
		}
	}

	enum Refusal {
		/** Not an absolute http(s) URL. Maps to INVALID_DESTINATION. */
		NOT_HTTP,
		/** No host, or a host that cannot be resolved. Maps to DESTINATION_NOT_ALLOWED. */
		UNRESOLVABLE_HOST,
		/** Resolves inside a network the public cannot reach. DESTINATION_NOT_ALLOWED. */
		PRIVATE_ADDRESS,
		/** Points at this service, which would create a redirect loop. */
		SELF_REFERENCE
	}
}
