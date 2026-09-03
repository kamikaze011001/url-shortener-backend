package com.sonanh.urlshortener.links.web;

import com.sonanh.urlshortener.links.usecase.CreateLinkUseCase;
import com.sonanh.urlshortener.shared.config.AppProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Translates HTTP into a Command and a Result back into HTTP. Nothing else — a
 * controller that decides anything has taken work that belongs to a use case.
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

	@PostMapping
	ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
		// STEP 2: no authentication yet, so every Link is attributed to the seeded dev
		// Owner. Replaced by the authenticated principal in step 4.
		var command = new CreateLinkUseCase.Command(
				properties.devOwnerId(),
				request.destination(),
				request.alias(),
				request.expiresAt());

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(LinkResponse.from(createLink.execute(command)));
	}
}
