package com.sonanh.urlshortener.links.domain;

import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import java.net.URI;
import java.net.URISyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single gate every Destination passes through, on creation <b>and</b> on update.
 *
 * <p>This class exists because the check was originally private to
 * {@code CreateLinkUseCase}. Adding an update path without extracting it would have
 * left a hole big enough to drive the whole SSRF guard through: create a Link to a
 * harmless URL, then PATCH it to {@code http://192.168.1.1/admin}. A security rule
 * applied at one of two entry points is not applied.
 */
@Component
public class DestinationPolicy {

	private static final Logger log = LoggerFactory.getLogger(DestinationPolicy.class);

	private final DestinationScreener screener;

	DestinationPolicy(DestinationScreener screener) {
		this.screener = screener;
	}

	/**
	 * @throws ApiException INVALID_DESTINATION when it is not an absolute http(s) URL,
	 *                      DESTINATION_NOT_ALLOWED when it points somewhere we refuse.
	 */
	public void check(String destination) {
		URI uri = parse(destination);
		DestinationScreener.Verdict verdict = screener.screen(uri);

		if (verdict.allowed()) {
			return;
		}

		// NOT_HTTP is about the shape of the URL; every other refusal is about where it
		// points. The distinction is what lets a client tell "fix this field" from
		// "we will not shorten that".
		if (verdict.reason() == DestinationScreener.Refusal.NOT_HTTP) {
			log.info("link.destination_rejected reason=INVALID_DESTINATION");
			throw new ApiException(ProblemCode.INVALID_DESTINATION,
					"The destination must be an absolute http or https URL.");
		}
		throw new ApiException(ProblemCode.DESTINATION_NOT_ALLOWED,
				"That destination cannot be shortened.");
	}

	/**
	 * A string that will not parse as a URI at all is still INVALID_DESTINATION rather
	 * than VALIDATION_FAILED: the field is present and the right length, so the problem
	 * is with its meaning, not its structure.
	 */
	private URI parse(String destination) {
		try {
			return new URI(destination);
		}
		catch (URISyntaxException ex) {
			log.info("link.destination_rejected reason=INVALID_DESTINATION");
			throw new ApiException(ProblemCode.INVALID_DESTINATION,
					"The destination is not a valid URL.");
		}
	}
}
