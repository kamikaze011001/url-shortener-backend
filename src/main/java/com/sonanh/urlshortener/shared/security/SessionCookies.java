package com.sonanh.urlshortener.shared.security;

import com.sonanh.urlshortener.shared.config.AppProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The session cookie, built in one place so its flags cannot drift apart.
 *
 * <p>{@code HttpOnly} means the frontend cannot read the token — which is the point,
 * and the reason "am I logged in?" is answered by calling {@code /auth/me} rather than
 * by inspecting storage.
 *
 * <p>{@code SameSite=Strict} is what stands in for a CSRF token here: the dashboard and
 * the API share an origin, so no legitimate request is cross-site, and every
 * cross-site form post arrives without the cookie. That is defence-in-depth reduced to
 * one depth, and it is recorded as such in 02-nfr.md.
 */
@Component
public class SessionCookies {

	public static final String NAME = "session";

	private final boolean secure;

	SessionCookies(AppProperties properties) {
		// False on localhost: a Secure cookie is never set over plain HTTP, so a true
		// default would break login locally and only locally.
		this.secure = properties.security().cookieSecure();
	}

	public ResponseCookie issue(String token, Duration ttl) {
		return base(token).maxAge(ttl).build();
	}

	/** Same attributes, zero lifetime — a cookie is only replaced by an identical one. */
	public ResponseCookie clear() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path("/");
	}
}
