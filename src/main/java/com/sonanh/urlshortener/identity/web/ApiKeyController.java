package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.identity.usecase.ManageApiKeysUseCase;
import com.sonanh.urlshortener.shared.security.CurrentOwner;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every method here begins with {@code requireSessionCredential()}, and that repetition
 * is the feature (FR-8.5). A key that could reach these endpoints could mint more keys
 * and revoke the Owner's real ones, turning one leaked credential into a foothold.
 */
@RestController
@RequestMapping("/api/v1/api-keys")
class ApiKeyController {

	private final ManageApiKeysUseCase apiKeys;
	private final CurrentOwner currentOwner;

	ApiKeyController(ManageApiKeysUseCase apiKeys, CurrentOwner currentOwner) {
		this.apiKeys = apiKeys;
		this.currentOwner = currentOwner;
	}

	@GetMapping
	List<ApiKeyResponse> list() {
		currentOwner.requireSessionCredential();

		return apiKeys.list(currentOwner.id()).stream().map(ApiKeyResponse::from).toList();
	}

	/** The only response in the system that ever contains key material. */
	@PostMapping
	ResponseEntity<ApiKeyCreatedResponse> create(@Valid @RequestBody CreateApiKeyRequest request) {
		currentOwner.requireSessionCredential();

		var created = apiKeys.create(currentOwner.id(), request.name(), request.toScopes(),
				request.expiresInDays());

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiKeyCreatedResponse.from(created));
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> revoke(@PathVariable long id) {
		currentOwner.requireSessionCredential();

		apiKeys.revoke(currentOwner.id(), id);
		return ResponseEntity.noContent().build();
	}
}
