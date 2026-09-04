package com.sonanh.urlshortener.links.web;

import tools.jackson.databind.JsonNode;
import com.sonanh.urlshortener.links.usecase.CreateLinkUseCase;
import com.sonanh.urlshortener.links.usecase.DeleteLinkUseCase;
import com.sonanh.urlshortener.links.usecase.GetLinkHistoryUseCase;
import com.sonanh.urlshortener.links.usecase.GetLinkUseCase;
import com.sonanh.urlshortener.links.usecase.ListLinksUseCase;
import com.sonanh.urlshortener.links.usecase.UpdateLinkUseCase;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.security.CurrentOwner;
import com.sonanh.urlshortener.shared.security.Scope;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Translates HTTP into a Command and a Result back into HTTP. Nothing else — a
 * controller that decides anything has taken work that belongs to a use case.
 */
@RestController
@RequestMapping("/api/v1/links")
class LinkController {

	private static final int MAX_PAGE_SIZE = 100;

	private final CreateLinkUseCase createLink;
	private final ListLinksUseCase listLinks;
	private final GetLinkUseCase getLink;
	private final GetLinkHistoryUseCase getHistory;
	private final UpdateLinkUseCase updateLink;
	private final DeleteLinkUseCase deleteLink;
	private final CurrentOwner currentOwner;

	LinkController(CreateLinkUseCase createLink, ListLinksUseCase listLinks, GetLinkUseCase getLink,
			GetLinkHistoryUseCase getHistory, UpdateLinkUseCase updateLink, DeleteLinkUseCase deleteLink,
			CurrentOwner currentOwner) {
		this.createLink = createLink;
		this.listLinks = listLinks;
		this.getLink = getLink;
		this.getHistory = getHistory;
		this.updateLink = updateLink;
		this.deleteLink = deleteLink;
		this.currentOwner = currentOwner;
	}

	/**
	 * FR-4.9. The record this returns has been written since ADR-0009; this is the first
	 * thing that reads it, which is what turns that ADR's promise into a visible feature.
	 */
	@GetMapping("/{id}/history")
	java.util.List<DestinationChangeResponse> history(@PathVariable long id) {
		currentOwner.requireScope(Scope.LINKS_READ);

		return getHistory.execute(new GetLinkHistoryUseCase.Command(currentOwner.id(), id)).stream()
				.map(DestinationChangeResponse::from)
				.toList();
	}

	@PostMapping
	ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
		// Both are questions about the caller, asked at the edge rather than inside the
		// use case, which has no business reading the security context.
		//
		// Verification first, and the order is deliberate: an unverified Owner cannot
		// create Links with any credential (FR-8.8), so telling them to widen a key's
		// scopes would send them to fix the wrong thing.
		currentOwner.requireVerified();
		currentOwner.requireScope(Scope.LINKS_WRITE);

		var command = new CreateLinkUseCase.Command(
				currentOwner.id(), request.destination(), request.alias(), request.expiresAt());

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(LinkResponse.from(createLink.execute(command)));
	}

	@GetMapping
	LinkPageResponse list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) String status) {

		currentOwner.requireScope(Scope.LINKS_READ);

		var result = listLinks.execute(new ListLinksUseCase.Command(
				currentOwner.id(), search, status, Math.max(page, 0), clampSize(size)));

		return LinkPageResponse.from(result);
	}

	@GetMapping("/{id}")
	LinkResponse get(@PathVariable String id) {
		currentOwner.requireScope(Scope.LINKS_READ);

		return LinkResponse.from(getLink.execute(
				new GetLinkUseCase.Command(currentOwner.id(), linkId(id))));
	}

	@PatchMapping("/{id}")
	LinkResponse update(@PathVariable String id, @RequestBody JsonNode body) {
		currentOwner.requireScope(Scope.LINKS_WRITE);

		// Read as raw JSON, not a bound record: a PATCH must tell an absent field from
		// an explicit null, and binding cannot. See UpdateLinkRequest.
		UpdateLinkRequest request = UpdateLinkRequest.from(body);

		var command = new UpdateLinkUseCase.Command(
				currentOwner.id(),
				linkId(id),
				request.destination(),
				request.status(),
				request.changesExpiry(),
				request.expiresAt());

		return LinkResponse.from(updateLink.execute(command));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable String id) {
		currentOwner.requireScope(Scope.LINKS_WRITE);

		deleteLink.execute(new DeleteLinkUseCase.Command(currentOwner.id(), linkId(id)));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Ids are opaque strings in the contract even though they are bigints today. A
	 * non-numeric id is NOT_FOUND rather than a parse error: to a caller, "not a valid
	 * id" and "no such link" are the same fact, and distinguishing them says something
	 * about our storage.
	 */
	private long linkId(String id) {
		try {
			return Long.parseLong(id);
		}
		catch (NumberFormatException ex) {
			throw ApiException.notFound("No such link.");
		}
	}

	private int clampSize(int size) {
		return Math.clamp(size, 1, MAX_PAGE_SIZE);
	}
}
