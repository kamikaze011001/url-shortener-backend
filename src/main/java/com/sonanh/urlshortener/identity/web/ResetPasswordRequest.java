package com.sonanh.urlshortener.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record ResetPasswordRequest(

		@NotBlank(message = "must not be blank")
		@Email(message = "must be a valid email address")
		@Size(max = 254, message = "must be at most 254 characters")
		String email,

		@NotBlank(message = "must not be blank")
		@Pattern(regexp = "^[0-9]{6}$", message = "must be six digits")
		String code,

		@NotBlank(message = "must not be blank")
		@Size(min = 8, max = 128, message = "must be between 8 and 128 characters")
		String password
) {}
