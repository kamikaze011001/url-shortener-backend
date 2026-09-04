package com.sonanh.urlshortener.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record VerifyEmailRequest(

		@NotBlank(message = "must not be blank")
		@Pattern(regexp = "^[0-9]{6}$", message = "must be six digits")
		String code
) {}
