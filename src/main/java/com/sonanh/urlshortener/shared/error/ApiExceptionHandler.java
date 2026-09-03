package com.sonanh.urlshortener.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
	 * Bean validation failures: the field is missing, the wrong length, or the wrong
	 * shape. Structural, so 400 — as opposed to a well-formed field we decline on
	 * merit, which is 422.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex, WebRequest request) {
		ProblemDetail problem = problem(ProblemCode.VALIDATION_FAILED,
				"One or more fields are invalid.", request);

		problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
				.map(error -> Map.of(
						"field", error.getField(),
						"message", String.valueOf(error.getDefaultMessage())))
				.toList());
		return problem;
	}

	/**
	 * Unparseable JSON, or a value that cannot become the declared type. The parser's
	 * message is deliberately dropped: it names Java classes and field paths, which is
	 * information about our internals rather than about the caller's mistake.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, WebRequest request) {
		return problem(ProblemCode.VALIDATION_FAILED, "The request body could not be read.", request);
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
