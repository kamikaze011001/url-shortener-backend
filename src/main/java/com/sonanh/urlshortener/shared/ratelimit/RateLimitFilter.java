package com.sonanh.urlshortener.shared.ratelimit;

import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.error.ProblemWriter;
import com.sonanh.urlshortener.shared.http.ClientRequest;
import com.sonanh.urlshortener.shared.security.AuthenticatedOwner;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces FR-6.
 *
 * <p>Runs after {@code JwtCookieAuthFilter}, because the per-Owner limit needs a
 * principal, and before the endpoints themselves, because the cheapest request to serve
 * is one that never reaches a use case.
 *
 * <p><b>Deliberately not a Spring bean</b>, for the same reason as
 * {@code JwtCookieAuthFilter}: {@code spring-modulith-starter-insight} wraps beans inside
 * application modules in CGLIB proxies, and {@code GenericFilterBean.init} is final, so
 * the proxy silently skips it and the filter starts with a null logger — which takes the
 * whole web server down at boot. {@code SecurityConfig} constructs it instead.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	/** FR-6.1. Register shares it: creating accounts in bulk is at least as abusable. */
	private static final RateLimitPolicy AUTH = RateLimitPolicy.perMinute("auth-ip", 5);

	/** FR-6.2 and FR-6.3 — both apply to one request, and both must pass. */
	private static final RateLimitPolicy CREATE_PER_IP = RateLimitPolicy.perMinute("create-ip", 10);
	private static final RateLimitPolicy CREATE_PER_OWNER = RateLimitPolicy.perHour("create-owner", 100);

	/** FR-6.4. Far above human clicking, low enough to blunt a scripted sweep. */
	private static final RateLimitPolicy REDIRECT = RateLimitPolicy.perMinute("redirect-ip", 100);

	/** FR-6.7, per-IP half. The per-address half lives in the use case — see below. */
	private static final RateLimitPolicy RESET_REQUEST_IP = RateLimitPolicy.perHour("reset-ip", 3);

	/** FR-6.8 — asking for a code. Both windows apply. */
	private static final RateLimitPolicy CODE_SEND_MINUTE = RateLimitPolicy.perMinute("code-send-owner", 1);
	private static final RateLimitPolicy CODE_SEND_HOUR = RateLimitPolicy.perHour("code-send-owner-hour", 5);

	/** FR-6.9 — submitting a code, on top of the 5 attempts each code carries. */
	private static final RateLimitPolicy CODE_SUBMIT = RateLimitPolicy.perHour("code-submit-owner", 10);

	/**
	 * FR-6.10. Higher than a browser's ten, because a script making a request a second
	 * is working rather than attacking — and keyed on the Owner behind the key rather
	 * than the IP, because automation runs from shared cloud addresses where per-IP
	 * would let one tenant exhaust the ceiling for every unrelated tenant beside them.
	 */
	private static final RateLimitPolicy CREATE_PER_KEY = RateLimitPolicy.perMinute("create-key", 60);

	/** The same shape the security chain and the controller use for a Short Code. */
	private static final Pattern SHORT_CODE_PATH = Pattern.compile("/[A-Za-z0-9_-]{3,32}");

	private final RedisRateLimiter limiter;
	private final ClientRequest client;
	private final MeterRegistry meters;

	public RateLimitFilter(RedisRateLimiter limiter, ClientRequest client, MeterRegistry meters) {
		this.limiter = limiter;
		this.client = client;
		this.meters = meters;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {

		for (Check check : checksFor(request)) {
			RateLimitVerdict verdict = limiter.check(check.key(), check.policy());
			if (!verdict.allowed()) {
				reject(response, check.policy(), verdict);
				return;
			}
		}

		chain.doFilter(request, response);
	}

	/**
	 * A request can be subject to more than one limit, and every one of them consumes
	 * its allowance even when a later one rejects. That is the ordinary behaviour of
	 * layered limits: the per-IP counter is measuring what the IP asked for, not what it
	 * was granted.
	 */
	private List<Check> checksFor(HttpServletRequest request) {
		String path = request.getRequestURI();
		HttpMethod method = HttpMethod.valueOf(request.getMethod());
		List<Check> checks = new ArrayList<>(2);

		if (method == HttpMethod.POST
				&& (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register"))) {
			checks.add(new Check(AUTH, clientKey(request)));
		}
		else if (method == HttpMethod.POST && path.equals("/api/v1/links")) {
			// A keyed request gets its own ceiling instead of the browser's, and skips
			// the per-IP bucket entirely: an IP limit on automation is a limit on
			// whichever datacenter it happens to run in.
			if (authenticatedVia() == AuthenticatedOwner.Credential.API_KEY) {
				ownerId().ifPresent(owner -> checks.add(new Check(CREATE_PER_KEY, owner.toString())));
			}
			else {
				checks.add(new Check(CREATE_PER_IP, clientKey(request)));
			}
			ownerId().ifPresent(owner -> checks.add(new Check(CREATE_PER_OWNER, owner.toString())));
		}
		else if (method == HttpMethod.GET && SHORT_CODE_PATH.matcher(path).matches()) {
			checks.add(new Check(REDIRECT, clientKey(request)));
		}
		else if (method == HttpMethod.POST && path.equals("/api/v1/auth/forgot-password")) {
			// Only the per-IP half is here. FR-6.7's second bucket keys on the target
			// address, which lives in the body — and a filter cannot read a body without
			// either consuming it or wrapping the request to replay it. It is enforced
			// in RequestPasswordResetUseCase instead, where the body is already parsed.
			checks.add(new Check(RESET_REQUEST_IP, clientKey(request)));
		}
		else if (method == HttpMethod.POST && path.equals("/api/v1/auth/resend-verification")) {
			ownerId().ifPresent(owner -> {
				checks.add(new Check(CODE_SEND_MINUTE, owner.toString()));
				checks.add(new Check(CODE_SEND_HOUR, owner.toString()));
			});
		}
		else if (method == HttpMethod.POST && path.equals("/api/v1/auth/verify-email")) {
			ownerId().ifPresent(owner -> checks.add(new Check(CODE_SUBMIT, owner.toString())));
		}

		return checks;
	}

	/**
	 * The salted hash, not the address. It is stable enough to count against and useless
	 * to anyone who reads the Redis keyspace — the same reason {@code click_events}
	 * stores a hash (02-nfr.md § Privacy).
	 */
	private String clientKey(HttpServletRequest request) {
		return client.ipHash(request);
	}

	private AuthenticatedOwner.Credential authenticatedVia() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication != null
				&& authentication.getPrincipal() instanceof AuthenticatedOwner owner)
				? owner.credential()
				: null;
	}

	private java.util.Optional<UUID> ownerId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication != null
				&& authentication.getPrincipal() instanceof AuthenticatedOwner owner)
				? java.util.Optional.of(owner.id())
				: java.util.Optional.empty();
	}

	/**
	 * FR-6.5. The headers are the ones the contract declares on this response, and
	 * {@code Retry-After} is the only one a well-behaved client needs — the other two
	 * exist so a human debugging a 429 can see which limit bit.
	 */
	private void reject(HttpServletResponse response, RateLimitPolicy policy, RateLimitVerdict verdict)
			throws IOException {

		// FR-7.2: tagged by policy, so the metric answers *which* limit is firing rather
		// than only that one did.
		meters.counter("urlshortener.ratelimit.rejections", "policy", policy.name()).increment();

		response.setHeader("Retry-After", String.valueOf(verdict.retryAfterSeconds()));
		response.setHeader("X-RateLimit-Limit", String.valueOf(verdict.limit()));
		response.setHeader("X-RateLimit-Remaining", String.valueOf(verdict.remaining()));

		ProblemWriter.write(response, ProblemCode.RATE_LIMITED,
				"Too many requests. Try again in " + verdict.retryAfterSeconds() + " seconds.");
	}

	private record Check(RateLimitPolicy policy, String key) {}
}
