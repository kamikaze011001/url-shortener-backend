package com.sonanh.urlshortener.links.usecase;

import com.sonanh.urlshortener.links.store.LinkRow;
import java.time.Instant;

/**
 * What every Link-returning use case hands back.
 *
 * <p>A shared result type rather than a {@code Result} nested in each use case
 * (ADR-0011's usual shape): four use cases returning the identical ten fields would be
 * four places to update when the contract gains one, and the wire shape is fixed by
 * {@code contracts/openapi.yaml} anyway.
 */
public record LinkView(
		long id,
		String code,
		String shortUrl,
		String destination,
		String status,
		boolean customAlias,
		long clickCount,
		Instant expiresAt,
		Instant createdAt,
		Instant updatedAt
) {

	/**
	 * {@code EXPIRED} is computed here, never stored. Storing it would need a job to
	 * write it and would be wrong for the whole window between the expiry moment and
	 * that job running; derived state cannot drift.
	 */
	static LinkView of(LinkRow row, String shortUrl, Instant now) {
		boolean expired = row.expiresAt() != null && !row.expiresAt().isAfter(now);

		return new LinkView(
				row.id(),
				row.code(),
				shortUrl,
				row.destination(),
				expired ? "EXPIRED" : row.status(),
				row.customAlias(),
				row.clickCount(),
				row.expiresAt(),
				row.createdAt(),
				row.updatedAt());
	}
}
