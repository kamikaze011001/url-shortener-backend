package com.sonanh.urlshortener.identity.store;

import com.sonanh.urlshortener.identity.domain.ApiKeys;
import com.sonanh.urlshortener.shared.security.ApiKeyAuthenticator;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Supplies {@code shared}'s {@link ApiKeyAuthenticator} from the {@code api_keys} table. */
@Component
class DbApiKeyAuthenticator implements ApiKeyAuthenticator {

	private static final Logger log = LoggerFactory.getLogger(DbApiKeyAuthenticator.class);

	/**
	 * {@code last_used_at} is refreshed at most this often.
	 *
	 * <p>Writing it on every request would put an UPDATE on the hot path of every keyed
	 * call — for a column whose only job is to answer "is anything still using this?"
	 * before someone revokes a key. Five minutes of staleness cannot change that answer,
	 * and the row is already in hand, so the check costs nothing.
	 */
	private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

	private final ApiKeyRepository keys;
	private final Clock clock;

	DbApiKeyAuthenticator(ApiKeyRepository keys, Clock clock) {
		this.keys = keys;
		this.clock = clock;
	}

	@Override
	public Optional<Authenticated> authenticate(String presentedKey) {
		// Rejected before the database is touched. An Authorization header full of
		// something else entirely is the common case, not an attack, and it should not
		// cost a query.
		if (!ApiKeys.looksLikeKey(presentedKey)) {
			return Optional.empty();
		}

		return keys.findByHash(ApiKeys.hash(presentedKey))
				.map(row -> {
					touchIfStale(row);
					return new Authenticated(row.ownerId(), row.emailVerified(), row.keyId());
				});
	}

	private void touchIfStale(ApiKeyRepository.AuthRow row) {
		var now = clock.instant();
		if (row.lastUsedAt() != null && row.lastUsedAt().isAfter(now.minus(TOUCH_INTERVAL))) {
			return;
		}
		try {
			keys.touch(row.keyId(), now);
		}
		catch (RuntimeException ex) {
			// Best effort, always. Bookkeeping about a key must never be the reason a
			// request that authenticated correctly fails — the same posture ClickRecorder
			// takes on the redirect path (ADR-0005).
			log.warn("apikey.touch_failed keyId={} reason={}", row.keyId(), ex.toString());
		}
	}
}
