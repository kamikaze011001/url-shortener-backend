package com.sonanh.urlshortener.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

/**
 * Every error response in the system is produced here, in RFC 9457
 * {@code application/problem+json}. One shape, one place.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ProblemDetail handleApiException(ApiException ex, WebRequest request) {
		return problem(ex.code(), ex.getMessage(), request);
	}

	/**
	 * The catch-all. Note what does <b>not</b> happen: the exception message never
	 * reaches the client. An unexpected failure is always an opaque INTERNAL, because
	 * the alternative is leaking SQL fragments and class names to whoever asks.
	 */
	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex, WebRequest request) {
		log.error("unhandled exception", ex);
		return problem(ProblemCode.INTERNAL, "An unexpected error occurred.", request);
	}

	private ProblemDetail problem(ProblemCode code, String detail, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatus(code.status());
		problem.setType(java.net.URI.create(code.type()));
		problem.setTitle(code.title());
		problem.setDetail(detail);
		problem.setProperty("code", code.name());

		Object path = request.getAttribute(
				"org.springframework.web.servlet.HandlerMapping.pathWithinHandlerMapping",
				RequestAttributes.SCOPE_REQUEST);
		if (path != null) {
			problem.setInstance(java.net.URI.create(path.toString()));
		}
		return problem;
	}
}
