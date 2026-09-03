package com.sonanh.urlshortener.shared.config;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
		List<String> extraBlockedHosts,
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

	/**
	 * Hostnames that may never be a Destination: our own, plus anything configured.
	 *
	 * <p>Derived from {@link #shortBaseUrl} rather than listed separately, so the two
	 * cannot drift apart when the service moves to a real domain. In production the
	 * short host resolves to Cloudflare's public addresses, so the private-address rule
	 * would never catch a self-reference — only this list does.
	 */
	public Set<String> blockedHosts() {
		Set<String> hosts = new HashSet<>();
		if (extraBlockedHosts != null) {
			extraBlockedHosts.forEach(host -> hosts.add(host.toLowerCase(Locale.ROOT)));
		}
		try {
			String host = URI.create(shortBaseUrl).getHost();
			if (host != null) {
				hosts.add(host.toLowerCase(Locale.ROOT));
			}
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("app.short-base-url is not a valid URI: " + shortBaseUrl, ex);
		}
		return Set.copyOf(hosts);
	}
}
