package com.sonanh.urlshortener.shared;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable half of an error response.
 *
 * <p>These strings are <b>part of the API contract</b>: the frontend switches on them,
 * so renaming one is a breaking change. The human-readable {@code title} and
 * {@code detail} are not — they may be reworded freely.
 *
 * @see <a href="../../../../../../contracts/openapi.yaml">contracts/openapi.yaml</a>
 */
public enum ProblemCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Not authenticated"),

	/**
	 * Deliberately overloaded: also returned when a resource exists but belongs to
	 * another Owner. A 403 would confirm it exists (ADR-0008).
	 */
	NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

	ALIAS_TAKEN(HttpStatus.CONFLICT, "Alias already taken"),
	RESERVED_ALIAS(HttpStatus.CONFLICT, "Alias is reserved"),
	EMAIL_TAKEN(HttpStatus.CONFLICT, "Email already registered"),
	INVALID_DESTINATION(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid destination"),
	DESTINATION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "Destination not allowed"),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
	INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private final HttpStatus status;
	private final String title;

	ProblemCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

	public String type() {
		return "https://url-shortener.invalid/problems/" + name().toLowerCase().replace('_', '-');
	}
}
