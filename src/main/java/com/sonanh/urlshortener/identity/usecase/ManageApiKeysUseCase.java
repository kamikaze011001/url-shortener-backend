package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.domain.ApiKeys;
import com.sonanh.urlshortener.identity.store.ApiKeyRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
			Instant lastUsedAt, Instant createdAt) {}

	/** @param plaintext the only time this value exists outside the caller's machine. */
	public record Created(Key key, String plaintext) {}

	/**
	 * The plaintext is returned once and then unrecoverable — only its SHA-256 is
	 * stored. An Owner who loses it revokes and creates another, which is a worse
	 * experience than looking it up and a much better one than a database leak handing
	 * over every live credential in the system.
	 */
	@Transactional
	public Created create(UUID ownerId, String name) {
		String plaintext = ApiKeys.generate();

		var row = keys.insert(ownerId, name, ApiKeys.hash(plaintext),
				ApiKeys.displayPrefix(plaintext), ApiKeys.last4(plaintext));

		log.info("apikey.created ownerId={} keyId={}", ownerId, row.id());
		return new Created(toKey(row), plaintext);
	}

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
				row.lastUsedAt(), row.createdAt());
	}
}
