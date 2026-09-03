package com.sonanh.urlshortener.shared.http;

import com.sonanh.urlshortener.shared.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Everything the application needs to know about who is making a request, and nothing
 * it must not keep.
 *
 * <p>The raw IP is used and discarded. It is never stored and never logged: an IP plus
 * a timestamp plus a destination identifies a person, and a URL shortener sees exactly
 * that for every Link its Owners share (02-nfr.md § Privacy).
 */
@Component
public class ClientRequest {

	private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
	private static final String CF_IP_COUNTRY = "CF-IPCountry";
	private static final String UNKNOWN_COUNTRY = "XX";

	private final AppProperties properties;

	ClientRequest(AppProperties properties) {
		this.properties = properties;
	}

	/**
	 * Cloudflare adds this header at the edge; it does not exist locally. An absent
	 * header is normal, not an error — returning "XX" rather than throwing is what
	 * keeps local development identical to production.
	 */
	public String country(HttpServletRequest request) {
		String country = request.getHeader(CF_IP_COUNTRY);
		return (country == null || country.length() != 2) ? UNKNOWN_COUNTRY : country.toUpperCase();
	}

	/**
	 * Salted SHA-256 of the client IP — enough to spot the same Visitor twice, useless
	 * for identifying them, harmless in a database leak.
	 *
	 * <p>{@code CF-Connecting-IP} is trusted only because this process is reachable
	 * only through the tunnel. If the port were ever exposed directly, this header
	 * would be trivially spoofable and every per-IP rate limit built on it would be
	 * decorative.
	 */
	public String ipHash(HttpServletRequest request) {
		String ip = request.getHeader(CF_CONNECTING_IP);
		if (ip == null || ip.isBlank()) {
			ip = request.getRemoteAddr();
		}
		return sha256(properties.clickHashSalt() + ":" + ip);
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}
}
