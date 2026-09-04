package com.sonanh.urlshortener.identity.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** The {@code api_keys} table. */
@Component
public class ApiKeyRepository {

	private static final String INSERT = """
			INSERT INTO api_keys (owner_id, name, key_hash, key_prefix, key_last4)
			VALUES (?, ?, ?, ?, ?)
			RETURNING id, owner_id, name, key_prefix, key_last4, last_used_at, created_at
			""";

	/**
	 * The authentication lookup, on every keyed request: one indexed read on the hash.
	 * It also joins the Owner's auth state, because a request authenticated by a key
	 * needs exactly what a cookie request needs — is this Owner verified — and fetching
	 * it here costs nothing over fetching the key alone.
	 */
	private static final String SELECT_BY_HASH = """
			SELECT k.id, k.owner_id, k.last_used_at, o.email_verified
			FROM api_keys k
			JOIN owners o ON o.id = k.owner_id
			WHERE k.key_hash = ? AND k.revoked_at IS NULL
			""";

	private static final String SELECT_FOR_OWNER = """
			SELECT id, owner_id, name, key_prefix, key_last4, last_used_at, created_at
			FROM api_keys
			WHERE owner_id = ? AND revoked_at IS NULL
			ORDER BY created_at DESC
			""";

	private static final String REVOKE = """
			UPDATE api_keys SET revoked_at = ?
			WHERE id = ? AND owner_id = ? AND revoked_at IS NULL
			""";

	private static final String TOUCH = "UPDATE api_keys SET last_used_at = ? WHERE id = ?";

	private static final RowMapper<ApiKeyRow> MAPPER = (rs, n) -> new ApiKeyRow(
			rs.getLong("id"),
			rs.getObject("owner_id", UUID.class),
			rs.getString("name"),
			rs.getString("key_prefix"),
			rs.getString("key_last4"),
			rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
			rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;

	ApiKeyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public ApiKeyRow insert(UUID ownerId, String name, String keyHash, String prefix, String last4) {
		return jdbc.query(INSERT, MAPPER, ownerId, name, keyHash, prefix, last4).getFirst();
	}

	public Optional<AuthRow> findByHash(String keyHash) {
		List<AuthRow> rows = jdbc.query(SELECT_BY_HASH,
				(rs, n) -> new AuthRow(rs.getLong("id"), rs.getObject("owner_id", UUID.class),
						rs.getBoolean("email_verified"),
						rs.getTimestamp("last_used_at") == null ? null
								: rs.getTimestamp("last_used_at").toInstant()),
				keyHash);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public List<ApiKeyRow> findForOwner(UUID ownerId) {
		return jdbc.query(SELECT_FOR_OWNER, MAPPER, ownerId);
	}

	/** @return true when a live key belonging to this Owner was revoked by this call. */
	public boolean revoke(long id, UUID ownerId, Instant now) {
		return jdbc.update(REVOKE, Timestamp.from(now), id, ownerId) == 1;
	}

	public void touch(long id, Instant now) {
		jdbc.update(TOUCH, Timestamp.from(now), id);
	}

	public record ApiKeyRow(long id, UUID ownerId, String name, String keyPrefix, String last4,
			Instant lastUsedAt, Instant createdAt) {}

	public record AuthRow(long keyId, UUID ownerId, boolean emailVerified, Instant lastUsedAt) {}
}
