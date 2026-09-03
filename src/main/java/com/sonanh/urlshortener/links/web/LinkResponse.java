package com.sonanh.urlshortener.links.web;

import com.sonanh.urlshortener.links.usecase.LinkView;
import java.time.Instant;

/**
 * Wire shape, fixed by {@code contracts/openapi.yaml}.
 *
 * <p>{@code shortUrl} arrives fully formed from the server. A client must never build
 * it by joining a base URL to a code — one place assembles that string, and it reads
 * the base from configuration.
 */
record LinkResponse(
		String id,
		String code,
		String shortUrl,
		String destination,
		String status,
		boolean isCustomAlias,
		long clickCount,
		Instant expiresAt,
		Instant createdAt,
		Instant updatedAt
) {

	static LinkResponse from(LinkView view) {
		return new LinkResponse(
				String.valueOf(view.id()),
				view.code(),
				view.shortUrl(),
				view.destination(),
				view.status(),
				view.customAlias(),
				view.clickCount(),
				view.expiresAt(),
				view.createdAt(),
				view.updatedAt());
	}
}
