package com.sonanh.urlshortener.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer sk_live_...} into an authenticated principal.
 *
 * <p>Runs <b>before</b> the cookie filter, which is how FR-8.6's "the key wins" is
 * implemented: this one authenticates first, and the cookie filter leaves an already
 * authenticated context alone. The header is explicit and the cookie is ambient, and a
 * request that half-used each is the kind of thing that turns into a privilege bug.
 *
 * <p>Silent about failure, like the cookie filter: a bad key leaves the context
 * anonymous and lets the security chain decide. Rejecting here would 401 the public
 * redirect path for anyone whose client sends a stale header.
 *
 * <p><b>Deliberately not a Spring bean</b>, for the CGLIB reason documented on
 * {@code JwtCookieAuthFilter}. {@code SecurityConfig} constructs it.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";

	private final ApiKeyAuthenticator authenticator;

	public ApiKeyAuthFilter(ApiKeyAuthenticator authenticator) {
		this.authenticator = authenticator;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		bearerToken(request)
				.flatMap(authenticator::authenticate)
				.ifPresent(owner -> {
					var principal = AuthenticatedOwner.viaApiKey(
							owner.ownerId(), owner.emailVerified(), owner.scopes());
					SecurityContextHolder.getContext()
							.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));

					MDC.put("ownerId", owner.ownerId().toString());
					// Which key, never the key. A log line is exactly the wrong place for
					// a live credential to end up.
					MDC.put("apiKeyId", String.valueOf(owner.keyId()));
				});

		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove("apiKeyId");
			// The cookie filter clears ownerId and the context; doing it here too would
			// be harmless but would hide which filter owns the lifecycle.
		}
	}

	private Optional<String> bearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return (header != null && header.startsWith(BEARER))
				? Optional.of(header.substring(BEARER.length()).trim())
				: Optional.empty();
	}
}
