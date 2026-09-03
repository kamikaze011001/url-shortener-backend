package com.sonanh.urlshortener.links.web;

import com.sonanh.urlshortener.links.usecase.CreateLinkUseCase;
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

	static LinkResponse from(CreateLinkUseCase.Result result) {
		return new LinkResponse(
				String.valueOf(result.id()),
				result.code(),
				result.shortUrl(),
				result.destination(),
				result.status(),
				result.customAlias(),
				result.clickCount(),
				result.expiresAt(),
				result.createdAt(),
				result.updatedAt());
	}
}
