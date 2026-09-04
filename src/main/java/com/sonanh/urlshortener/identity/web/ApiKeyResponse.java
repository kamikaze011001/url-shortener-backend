package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.identity.usecase.ManageApiKeysUseCase;
import com.sonanh.urlshortener.shared.security.Scope;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. Never carries key material. */
record ApiKeyResponse(String id, String name, String keyPrefix, String last4,
		List<String> scopes, Instant expiresAt, Instant lastUsedAt, Instant createdAt) {

	static ApiKeyResponse from(ManageApiKeysUseCase.Key key) {
		return new ApiKeyResponse(key.id(), key.name(), key.keyPrefix(), key.last4(),
				wireNames(key.scopes()), key.expiresAt(), key.lastUsedAt(), key.createdAt());
	}

	/**
	 * Sorted, so the array is stable across requests. The set has no order and JSON
	 * arrays do, and a list that reshuffles between two identical reads is the kind of
	 * thing that makes a client's diff noisy for no reason.
	 */
	static List<String> wireNames(Set<Scope> scopes) {
		return scopes.stream().map(Scope::wireName).sorted().toList();
	}
}
