package com.sonanh.urlshortener.identity.store;

import com.sonanh.urlshortener.shared.security.OwnerAuthState;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Supplies {@code shared}'s {@link OwnerAuthState} from the {@code owners} table.
 *
 * <p>This class is the other half of the inversion described on that interface:
 * {@code identity} owns the table, so the implementation lives here, and
 * {@code shared} never names an {@code identity} type.
 *
 * <p><b>Redis caches; Postgres decides.</b> A miss, a Redis outage, or a serialisation
 * this code no longer understands all fall through to the table and answer correctly —
 * slower, never wrong. That is ADR-0004 applied to a new reader rather than a new rule,
 * and it is what keeps a cache failure from either locking everyone out or letting
 * revoked sessions back in.
 */
@Component
class CachedOwnerAuthState implements OwnerAuthState {

	private static final Logger log = LoggerFactory.getLogger(CachedOwnerAuthState.class);

	private static final String KEY_PREFIX = "owner:auth:";

	/**
	 * A backstop, not the mechanism. Every change calls {@link #invalidate}, so the TTL
	 * only matters if that eviction was itself lost — a Redis blip between the write and
	 * the delete. Five minutes bounds how long a stale answer could survive that.
	 */
	private static final Duration TTL = Duration.ofMinutes(5);

	private final OwnerRepository owners;
	private final StringRedisTemplate redis;

	CachedOwnerAuthState(OwnerRepository owners, StringRedisTemplate redis) {
		this.owners = owners;
		this.redis = redis;
	}

	@Override
	public Optional<State> find(UUID ownerId) {
		String key = KEY_PREFIX + ownerId;

		Optional<State> cached = read(key);
		if (cached.isPresent()) {
			return cached;
		}

		Optional<State> fresh = owners.findAuthState(ownerId)
				.map(row -> new State(row.tokenVersion(), row.emailVerified()));

		// A missing Owner is not cached. It is rare, it costs one indexed lookup, and
		// caching absence means a deleted-then-recreated id could answer stale.
		fresh.ifPresent(state -> write(key, state));

		return fresh;
	}

	@Override
	public void invalidate(UUID ownerId) {
		try {
			redis.delete(KEY_PREFIX + ownerId);
		}
		catch (RuntimeException ex) {
			// Losing the eviction is survivable — the TTL bounds it — but it is the one
			// case where a stale answer can outlive a revocation, so it is logged loudly.
			log.warn("ownerauth.invalidate_failed ownerId={} reason={}", ownerId, ex.toString());
		}
	}

	private Optional<State> read(String key) {
		try {
			String value = redis.opsForValue().get(key);
			if (value == null) {
				return Optional.empty();
			}
			int separator = value.indexOf(':');
			return Optional.of(new State(
					Integer.parseInt(value.substring(0, separator)),
					Boolean.parseBoolean(value.substring(separator + 1))));
		}
		catch (RuntimeException ex) {
			// Includes Redis being unreachable and a value this build cannot parse.
			// Both mean "no cached answer", which is a state this class already handles.
			return Optional.empty();
		}
	}

	private void write(String key, State state) {
		try {
			redis.opsForValue().set(key, state.tokenVersion() + ":" + state.emailVerified(), TTL);
		}
		catch (RuntimeException ex) {
			// Failing to populate a cache is not a failure of the request.
			log.debug("ownerauth.cache_write_failed reason={}", ex.toString());
		}
	}
}
