package com.sonanh.urlshortener.analytics.domain;

import java.net.URI;

/**
 * Reduces a stored referrer to the host that sent the Visitor.
 *
 * <p>The raw header is kept in {@code click_events} because it is evidence and cannot be
 * reconstructed later, but it is useless as a grouping key: a hundred visits from one
 * site arrive as a hundred distinct paths and query strings, and the breakdown becomes a
 * list of a hundred rows each reading 1. The host is the thing an Owner actually wants
 * to know.
 */
public final class ReferrerHost {

	private ReferrerHost() {
	}

	/**
	 * @return the host, or {@code null} when the Visitor arrived directly or the header
	 *         was unparseable. Both collapse to "direct" on purpose: a referrer that is
	 *         not a URL tells the Owner nothing, and inventing a bucket for it would
	 *         give a malformed header a permanent seat in the chart.
	 */
	public static String of(String referrer) {
		if (referrer == null || referrer.isBlank()) {
			return null;
		}
		try {
			String host = URI.create(referrer).getHost();
			return (host == null || host.isBlank()) ? null : host;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
