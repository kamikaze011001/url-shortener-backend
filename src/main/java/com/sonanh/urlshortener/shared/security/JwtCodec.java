package com.sonanh.urlshortener.shared.security;

import com.sonanh.urlshortener.shared.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies session tokens.
 *
 * <p>Lives in {@code shared} rather than {@code identity} on purpose: {@code identity}
 * issues tokens, but the filter that verifies them runs in front of every module's
 * endpoints. Putting the codec in {@code identity} would force a port that exists only
 * to satisfy the module graph — a JWT is a mechanism, not a subject.
 *
 * <p><b>A token carries the Owner's token version</b>, and the filter compares it with
 * the current one on every authenticated request. That is what lets a password reset
 * end every existing session (ADR-0018). The check does not touch the redirect path,
 * which is unauthenticated, so it never runs where the latency budget is tight.
 */
@Component
public class JwtCodec {

	private static final String VERSION_CLAIM = "ver";

	private final SecretKey key;
	private final java.time.Duration ttl;

	JwtCodec(AppProperties properties) {
		byte[] secret = properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
		if (secret.length < 32) {
			// HS256 needs 256 bits. Failing at startup beats failing on first login.
			throw new IllegalStateException("app.security.jwt-secret must be at least 32 bytes");
		}
		this.key = Keys.hmacShaKeyFor(secret);
		this.ttl = properties.security().sessionTtl();
	}

	public String issue(UUID ownerId, int tokenVersion, Instant now) {
		return Jwts.builder()
				.subject(ownerId.toString())
				.claim(VERSION_CLAIM, tokenVersion)
				.issuedAt(java.util.Date.from(now))
				.expiration(java.util.Date.from(now.plus(ttl)))
				.signWith(key)
				.compact();
	}

	/**
	 * @return the Owner id and the version the token was issued against, or empty when
	 *         the token is absent, malformed, tampered with or expired. The caller
	 *         treats every one of those the same way, so they are not distinguished.
	 */
	public Optional<Session> verify(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build()
					.parseSignedClaims(token).getPayload();

			// A token minted before the version claim existed reads as 0, which is the
			// column default — so sessions issued by the previous build stay valid
			// instead of logging everyone out on deploy.
			Integer version = claims.get(VERSION_CLAIM, Integer.class);

			return Optional.of(new Session(
					UUID.fromString(claims.getSubject()),
					version == null ? 0 : version));
		}
		catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public record Session(UUID ownerId, int tokenVersion) {}

	public java.time.Duration ttl() {
		return ttl;
	}
}
