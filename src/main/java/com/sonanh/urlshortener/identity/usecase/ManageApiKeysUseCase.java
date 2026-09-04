package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.domain.ApiKeys;
import com.sonanh.urlshortener.identity.store.ApiKeyRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.security.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-8: creating, listing and revoking API Keys.
 *
 * <p>Three operations in one class rather than three, which is a deliberate departure
 * from ADR-0011. That ADR exists because a "service" accretes unrelated behaviour until
 * nobody can say what it does; these three are the complete lifecycle of one small
 * thing, they share no logic worth extracting, and splitting them would produce three
 * files whose combined content is shorter than their imports.
 *
 * <p>If a fourth operation appears that is not "manage a key", it belongs elsewhere and
 * this note is the reason why.
 */
@Service
public class ManageApiKeysUseCase {

	private static final Logger log = LoggerFactory.getLogger(ManageApiKeysUseCase.class);

	private final ApiKeyRepository keys;
	private final Clock clock;

	ManageApiKeysUseCase(ApiKeyRepository keys, Clock clock) {
		this.keys = keys;
		this.clock = clock;
	}

	public record Key(String id, String name, String keyPrefix, String last4,
			Set<Scope> scopes, Instant expiresAt, Instant lastUsedAt, Instant createdAt) {}

	/** @param plaintext the only time this value exists outside the caller's machine. */
	public record Created(Key key, String plaintext) {}

	/**
	 * The plaintext is returned once and then unrecoverable — only its SHA-256 is
	 * stored. An Owner who loses it revokes and creates another, which is a worse
	 * experience than looking it up and a much better one than a database leak handing
	 * over every live credential in the system.
	 */
	/**
	 * @param expiresInDays null for a key that never expires (FR-8.7). The caller sends
	 *                      a duration rather than a timestamp: a client sending an
	 *                      absolute time needs a clock and a timezone and gets both
	 *                      wrong in the interesting cases, and the server owns the clock
	 *                      either way.
	 */
	@Transactional
	public Created create(UUID ownerId, String name, Set<Scope> scopes, Integer expiresInDays) {
		String plaintext = ApiKeys.generate();
		Instant expiresAt = expiresInDays == null
				? null
				: clock.instant().plus(Duration.ofDays(expiresInDays));

		var row = keys.insert(ownerId, name, ApiKeys.hash(plaintext),
				ApiKeys.displayPrefix(plaintext), ApiKeys.last4(plaintext), scopes, expiresAt);

		// Which scopes, and whether it dies — the two facts anyone reading this line
		// afterwards will want. Never the key itself.
		log.info("apikey.created ownerId={} keyId={} scopes={} expiresAt={}",
				ownerId, row.id(), scopes, expiresAt);
		return new Created(toKey(row), plaintext);
	}

	/**
	 * Includes keys that have expired (FR-8.11). They authenticate nothing, and this
	 * list is the only place their Owner can see the difference between "expired" and
	 * "wrong key" — the API answers an identical 401 to both.
	 */
	@Transactional(readOnly = true)
	public List<Key> list(UUID ownerId) {
		return keys.findForOwner(ownerId).stream().map(ManageApiKeysUseCase::toKey).toList();
	}

	/**
	 * Revoking someone else's key, or one that is already revoked, is NOT_FOUND — the
	 * same answer as a key that never existed, so an Owner cannot probe for other
	 * people's key ids (ADR-0008).
	 */
	@Transactional
	public void revoke(UUID ownerId, long keyId) {
		if (!keys.revoke(keyId, ownerId, clock.instant())) {
			throw ApiException.notFound("No such API key.");
		}
		log.info("apikey.revoked ownerId={} keyId={}", ownerId, keyId);
	}

	private static Key toKey(ApiKeyRepository.ApiKeyRow row) {
		return new Key(String.valueOf(row.id()), row.name(), row.keyPrefix(), row.last4(),
				row.scopes(), row.expiresAt(), row.lastUsedAt(), row.createdAt());
	}
}
