package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.identity.usecase.ManageApiKeysUseCase;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape, fixed by {@code contracts/openapi.yaml}.
 *
 * <p>A separate record from {@link ApiKeyResponse} rather than a nullable field on it.
 * The plaintext appears in exactly one response in the whole API, and a type that can
 * only be constructed at creation makes that structural — there is no path by which the
 * list endpoint could accidentally start returning it.
 */
record ApiKeyCreatedResponse(String id, String name, String keyPrefix, String last4,
		List<String> scopes, Instant expiresAt, Instant lastUsedAt, Instant createdAt,
		String key) {

	static ApiKeyCreatedResponse from(ManageApiKeysUseCase.Created created) {
		var k = created.key();
		return new ApiKeyCreatedResponse(k.id(), k.name(), k.keyPrefix(), k.last4(),
				ApiKeyResponse.wireNames(k.scopes()), k.expiresAt(), k.lastUsedAt(),
				k.createdAt(), created.plaintext());
	}
}
