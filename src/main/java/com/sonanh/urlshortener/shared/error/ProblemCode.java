package com.sonanh.urlshortener.shared.error;

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

	/**
	 * The Owner is signed in but has not confirmed their address (FR-1.7). The one
	 * deliberate exception to ADR-0008's uniform 404: the caller is asking about their
	 * own account, so there is nothing to conceal and something to explain.
	 */
	EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email not verified"),

	/** Wrong digits, already used, or too many attempts. */
	INVALID_CODE(HttpStatus.BAD_REQUEST, "Invalid code"),

	/** Past its ten-minute life. Separate from INVALID_CODE because the fix differs. */
	CODE_EXPIRED(HttpStatus.BAD_REQUEST, "Code expired"),

	/**
	 * The credential authenticated correctly but was not granted the scope this endpoint
	 * needs (FR-8.10). Distinct from FORBIDDEN because the fix differs: widen the key's
	 * scopes, rather than stop using a key at all.
	 */
	INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN, "Insufficient scope"),

	FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
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
