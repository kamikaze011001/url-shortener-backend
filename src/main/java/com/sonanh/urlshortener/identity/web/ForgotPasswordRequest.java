package com.sonanh.urlshortener.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record ForgotPasswordRequest(

		@NotBlank(message = "must not be blank")
		@Email(message = "must be a valid email address")
		@Size(max = 254, message = "must be at most 254 characters")
		String email
) {}
