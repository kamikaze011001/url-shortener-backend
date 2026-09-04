package com.sonanh.urlshortener.shared.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/**
 * Writes an RFC 9457 problem straight to the response.
 *
 * <p>For the places that answer before Spring MVC gets involved — the security entry
 * point and the rate-limit filter — where {@code ApiExceptionHandler} is not in play and
 * the alternative is Spring's HTML error page. Everything that reaches a controller
 * should throw {@link ApiException} and let the handler do this instead.
 *
 * <p>The JSON is assembled by hand rather than through Jackson: this runs on a path
 * where the ObjectMapper may not be reachable, and the shape is four fixed fields. The
 * detail is escaped because it is the only part that could carry a quote.
 */
public final class ProblemWriter {

	private ProblemWriter() {
	}

	public static void write(HttpServletResponse response, ProblemCode code, String detail)
			throws IOException {

		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		// Without this the container falls back to ISO-8859-1 and advertises it in the
		// Content-Type. JSON is UTF-8 by definition, and the first non-ASCII character in
		// a detail message would otherwise arrive mangled. The 401 path had this bug too;
		// it is fixed here because both now go through this method.
		response.setCharacterEncoding(StandardCharsets.UTF_8);
		response.getWriter().write("""
				{"type":"%s","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
				.formatted(code.type(), code.title(), code.status().value(), escape(detail), code.name()));
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
