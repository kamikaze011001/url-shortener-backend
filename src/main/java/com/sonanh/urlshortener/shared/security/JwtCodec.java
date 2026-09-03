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
 * <p><b>A token cannot be revoked before it expires.</b> There is no server-side
 * session and no deny list, so logout clears the cookie in the browser and nothing
 * more. Documented compromise; the fix is a token-version column on the Owner checked
 * per request, which costs a query per request and is why it is not here.
 */
@Component
public class JwtCodec {

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

	public String issue(UUID ownerId, Instant now) {
		return Jwts.builder()
				.subject(ownerId.toString())
				.issuedAt(java.util.Date.from(now))
				.expiration(java.util.Date.from(now.plus(ttl)))
				.signWith(key)
				.compact();
	}

	/**
	 * @return the Owner id, or empty when the token is absent, malformed, tampered with
	 *         or expired. The caller treats every one of those the same way, so they are
	 *         not worth distinguishing here.
	 */
	public Optional<UUID> verify(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).build()
					.parseSignedClaims(token).getPayload();
			return Optional.of(UUID.fromString(claims.getSubject()));
		}
		catch (JwtException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	public java.time.Duration ttl() {
		return ttl;
	}
}
