package com.sonanh.urlshortener.analytics;

import java.time.Instant;

/**
 * The seam from ADR-0005.
 *
 * <p>Today the implementation writes synchronously. Tomorrow it batches onto a bounded
 * queue; later it produces to Kafka. <b>The redirect path does not change at any of
 * those steps</b> — that is the entire return on this interface existing.
 *
 * <p>Two clauses that are contract, not style:
 * <ul>
 *   <li><b>Must not throw.</b> A Redirect must never fail because analytics failed.
 *       That is a correctness property from 02-nfr.md, not a performance concern.
 *   <li><b>Must not meaningfully block.</b> Recording happens after the redirect
 *       response has been decided, so a slow write can delay a response but can never
 *       change it.
 * </ul>
 */
public interface ClickRecorder {

	void record(ClickEvent event);

	/**
	 * @param ipHash    salted SHA-256 of the client IP. The raw IP is never stored and
	 *                  never logged (02-nfr.md § Privacy).
	 * @param country   two-letter code from CF-IPCountry, or "XX" — the header does not
	 *                  exist outside production and its absence is normal, not an error.
	 */
	record ClickEvent(
			long linkId,
			Instant occurredAt,
			String referrer,
			String userAgent,
			String country,
			String ipHash
	) {}
}
