package com.sonanh.urlshortener.identity.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately looser than {@link RegisterRequest}: applying the password length rule
 * here would tell an attacker which passwords are impossible before they are tried, and
 * would lock out anyone whose password predates a rule change.
 */
record LoginRequest(
		@NotBlank(message = "must not be blank") String email,
		@NotBlank(message = "must not be blank") String password
) {}
