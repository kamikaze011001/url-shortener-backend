package com.sonanh.urlshortener.links.web;

import com.sonanh.urlshortener.links.usecase.CreateLinkUseCase;
import com.sonanh.urlshortener.shared.security.CurrentOwner;
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
	private final CurrentOwner currentOwner;

	LinkController(CreateLinkUseCase createLink, CurrentOwner currentOwner) {
		this.createLink = createLink;
		this.currentOwner = currentOwner;
	}

	@PostMapping
	ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
		var command = new CreateLinkUseCase.Command(
				currentOwner.id(),
				request.destination(),
				request.alias(),
				request.expiresAt());

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(LinkResponse.from(createLink.execute(command)));
	}
}
