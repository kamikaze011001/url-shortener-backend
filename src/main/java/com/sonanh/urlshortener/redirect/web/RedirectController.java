package com.sonanh.urlshortener.redirect.web;

import com.sonanh.urlshortener.redirect.usecase.ResolveShortCodeUseCase;
import com.sonanh.urlshortener.shared.http.ClientRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The redirect endpoint. Served on the short host, where the entire root path
 * namespace belongs to Short Codes (ADR-0006).
 */
@RestController
class RedirectController {

	private static final String NOT_FOUND_BODY = """
			<!doctype html><meta charset=utf-8><title>Not found</title>
			<body style="font-family:system-ui;text-align:center;padding:4rem">
			<h1>404</h1><p>This link doesn't exist.</p>
			""";

	private final ResolveShortCodeUseCase resolve;
	private final ClientRequest clientRequest;

	RedirectController(ResolveShortCodeUseCase resolve, ClientRequest clientRequest) {
		this.resolve = resolve;
		this.clientRequest = clientRequest;
	}

	/**
	 * The path pattern is constrained so that {@code /actuator/health} and
	 * {@code /api/v1/links} are matched by their own handlers rather than being read as
	 * Short Codes. Reserved words close the remaining gap in step 3.
	 */
	@GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
	ResponseEntity<String> redirect(@PathVariable String code, HttpServletRequest request) {

		Optional<String> destination = resolve.execute(new ResolveShortCodeUseCase.Command(
				code,
				request.getHeader(HttpHeaders.REFERER),
				request.getHeader(HttpHeaders.USER_AGENT),
				clientRequest.country(request),
				clientRequest.ipHash(request)));

		return destination.map(this::found).orElseGet(this::notFound);
	}

	/**
	 * 302, never 301. A permanent redirect is cached by the browser forever, which
	 * would make Click counts meaningless and a Destination change invisible to anyone
	 * who had already followed the link (ADR-0003).
	 */
	private ResponseEntity<String> found(String destination) {
		return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, destination)
				.header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
				.header("X-Robots-Tag", "noindex")
				.build();
	}

	/** Identical for unknown, deleted, disabled and expired. */
	private ResponseEntity<String> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.contentType(MediaType.TEXT_HTML)
				.header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
				.body(NOT_FOUND_BODY);
	}
}
