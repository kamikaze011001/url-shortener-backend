package com.sonanh.urlshortener.links.web;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Wire shape, fixed by {@code contracts/openapi.yaml}. Renaming a field here is an API
 * change and belongs in the contract first.
 *
 * <p>These constraints cover <b>structure only</b> — present, right length, right
 * character set — and produce {@code 400 VALIDATION_FAILED}. Whether a well-formed URL
 * is one we are willing to shorten is a different question, answered later by
 * {@link com.sonanh.urlshortener.links.domain.DestinationScreener} with a {@code 422}.
 *
 * @param alias null to have a Short Code generated. When present it is claimed exactly
 *              or the request fails — never a silent fallback to a generated code.
 */
record CreateLinkRequest(

		@NotBlank(message = "must not be blank")
		@Size(max = 2048, message = "must be at most 2048 characters")
		String destination,

		@Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$",
				message = "must be 3-32 characters of letters, digits, hyphen or underscore")
		String alias,

		@Future(message = "must be in the future")
		Instant expiresAt
) {}
