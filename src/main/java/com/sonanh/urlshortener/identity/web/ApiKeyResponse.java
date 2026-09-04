package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.identity.usecase.ManageApiKeysUseCase;
import java.time.Instant;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. Never carries key material. */
record ApiKeyResponse(String id, String name, String keyPrefix, String last4,
		Instant lastUsedAt, Instant createdAt) {

	static ApiKeyResponse from(ManageApiKeysUseCase.Key key) {
		return new ApiKeyResponse(key.id(), key.name(), key.keyPrefix(), key.last4(),
				key.lastUsedAt(), key.createdAt());
	}
}
