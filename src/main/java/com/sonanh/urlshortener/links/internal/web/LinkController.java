package com.sonanh.urlshortener.links.internal.web;

import com.sonanh.urlshortener.links.CreateLinkUseCase;
import com.sonanh.urlshortener.shared.AppProperties;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maps HTTP to a Command and a Result back to HTTP. No business logic lives here —
 * that is the whole point of ADR-0011.
 */
@RestController
@RequestMapping("/api/v1/links")
class LinkController {

	private final CreateLinkUseCase createLink;
	private final AppProperties properties;

	LinkController(CreateLinkUseCase createLink, AppProperties properties) {
		this.createLink = createLink;
		this.properties = properties;
	}

	record CreateLinkRequest(String destination, String alias, Instant expiresAt) {}

	/** Field names and types are fixed by contracts/openapi.yaml. */
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
	) {}

	@PostMapping
	ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest request) {
		// STEP 2: there is no authentication yet, so every Link is attributed to the
		// seeded dev Owner. Replaced by the authenticated principal in step 4.
		CreateLinkUseCase.Result result = createLink.execute(new CreateLinkUseCase.Command(
				properties.devOwnerId(),
				request.destination(),
				request.alias(),
				request.expiresAt()));

		return ResponseEntity.status(HttpStatus.CREATED).body(new LinkResponse(
				String.valueOf(result.id()),
				result.code(),
				result.shortUrl(),
				result.destination(),
				result.status(),
				result.customAlias(),
				result.clickCount(),
				result.expiresAt(),
				result.createdAt(),
				result.updatedAt()));
	}
}
