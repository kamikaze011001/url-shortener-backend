package com.sonanh.urlshortener.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns the session cookie into an authenticated principal.
 *
 * <p>Deliberately silent about failure: a missing or invalid cookie leaves the context
 * anonymous and lets the request continue, so the security chain decides whether that
 * endpoint needed authentication. Rejecting here would 401 the public redirect path.
 *
 * <p><b>Deliberately not a Spring bean.</b> {@code spring-modulith-starter-insight}
 * wraps beans that live inside an application module in CGLIB proxies for observability,
 * and {@link org.springframework.web.filter.GenericFilterBean#init} is final — so the
 * proxy silently skips it and the filter starts with a null logger, taking the whole
 * web server down at boot. It is constructed by {@code SecurityConfig} instead, which
 * also keeps it out of the servlet container's own filter chain: it belongs to the
 * security chain and nowhere else.
 */
public class JwtCookieAuthFilter extends OncePerRequestFilter {

	private final JwtCodec jwt;

	public JwtCookieAuthFilter(JwtCodec jwt) {
		this.jwt = jwt;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		Optional<UUID> ownerId = sessionToken(request).flatMap(jwt::verify);

		ownerId.ifPresent(id -> {
			var authentication = new UsernamePasswordAuthenticationToken(id, null, List.of());
			SecurityContextHolder.getContext().setAuthentication(authentication);
			// Every log line inside this request carries the owner without being passed
			// one (03-architecture.md § Observability).
			MDC.put("ownerId", id.toString());
		});

		try {
			chain.doFilter(request, response);
		}
		finally {
			// Virtual threads are pooled by the JVM, not by us, but MDC is still
			// thread-local: leaving it set would leak an owner id into the next request
			// served by the same carrier.
			MDC.remove("ownerId");
			SecurityContextHolder.clearContext();
		}
	}

	private Optional<String> sessionToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		return Arrays.stream(cookies)
				.filter(cookie -> SessionCookies.NAME.equals(cookie.getName()))
				.map(Cookie::getValue)
				.filter(value -> value != null && !value.isBlank())
				.findFirst();
	}
}
