package com.sonanh.urlshortener.shared;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param shortBaseUrl the base of every Short Link this service hands out. It comes
 *                     from configuration and is <b>never</b> derived from the incoming
 *                     request. Deriving it works perfectly on localhost and then hands
 *                     out {@code http://localhost:8080/aB3xY9z} to real users on the
 *                     first deploy — the most common way a URL shortener breaks.
 * @param devOwnerId   TEMPORARY. Links require an Owner and authentication does not
 *                     exist yet, so the local profile seeds a fixed Owner and requests
 *                     are attributed to it. Deleted when auth lands.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		String shortBaseUrl,
		UUID devOwnerId,
		String clickHashSalt,
		Security security
) {

	public record Security(String jwtSecret, Duration sessionTtl, boolean cookieSecure) {}

	/** Builds the public Short Link for a code. The one place this string is assembled. */
	public String shortUrlFor(String code) {
		String base = shortBaseUrl.endsWith("/")
				? shortBaseUrl.substring(0, shortBaseUrl.length() - 1)
				: shortBaseUrl;
		return base + "/" + code;
	}
}
