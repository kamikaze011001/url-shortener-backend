package com.sonanh.urlshortener.links.web;

import tools.jackson.databind.JsonNode;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * A PATCH body, read from the raw JSON so that <b>key presence</b> can be observed.
 *
 * <p>Three instructions have to fit in one field:
 *
 * <pre>
 *   {}                              → leave the expiry alone
 *   {"expiresAt": null}             → clear the expiry
 *   {"expiresAt": "2031-01-01T..."} → set the expiry
 * </pre>
 *
 * <p>A bound record cannot express that. {@code Instant} collapses the first two into
 * null, and {@code Optional<Instant>} does not help either: <b>Jackson initialises an
 * absent Optional field to {@code Optional.empty()}, not to null</b>, so "absent" and
 * "explicitly null" arrive identically — a PATCH changing only the destination would
 * silently wipe the expiry. That bug was written, observed, and is the reason this
 * class reads {@link JsonNode#has} instead.
 *
 * <p>Validation is therefore by hand rather than by annotation. Two fields is a small
 * enough price for a correct PATCH.
 *
 * <p>Neither {@code code} nor {@code alias} is accepted. A Short Code cannot change:
 * renaming would release the old string into the Code Namespace for someone else to
 * claim (ADR-0008).
 */
record UpdateLinkRequest(String destination, String status, boolean changesExpiry, Instant expiresAt) {

	private static final int MAX_DESTINATION = 2048;

	static UpdateLinkRequest from(JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new ApiException(ProblemCode.VALIDATION_FAILED, "The request body must be an object.");
		}
		return new UpdateLinkRequest(
				destination(body), status(body), body.has("expiresAt"), expiresAt(body));
	}

	private static String destination(JsonNode body) {
		if (!body.has("destination") || body.get("destination").isNull()) {
			return null;
		}
		String value = body.get("destination").asText();
		if (value.isBlank() || value.length() > MAX_DESTINATION) {
			throw new ApiException(ProblemCode.VALIDATION_FAILED,
					"destination must be between 1 and " + MAX_DESTINATION + " characters.");
		}
		return value;
	}

	private static String status(JsonNode body) {
		if (!body.has("status") || body.get("status").isNull()) {
			return null;
		}
		String value = body.get("status").asText();
		if (!value.equals("ACTIVE") && !value.equals("DISABLED")) {
			throw new ApiException(ProblemCode.VALIDATION_FAILED, "status must be ACTIVE or DISABLED.");
		}
		return value;
	}

	private static Instant expiresAt(JsonNode body) {
		if (!body.has("expiresAt") || body.get("expiresAt").isNull()) {
			return null;
		}
		try {
			return Instant.parse(body.get("expiresAt").asText());
		}
		catch (DateTimeParseException ex) {
			throw new ApiException(ProblemCode.VALIDATION_FAILED,
					"expiresAt must be an ISO-8601 instant.");
		}
	}
}
