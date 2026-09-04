package com.sonanh.urlshortener.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record CreateApiKeyRequest(

		@NotBlank(message = "must not be blank")
		@Size(max = 64, message = "must be at most 64 characters")
		String name
) {}
