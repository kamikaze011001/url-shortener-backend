package com.sonanh.urlshortener.identity.store;

import com.sonanh.urlshortener.shared.security.Scope;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** The {@code api_keys} table. */
@Component
public class ApiKeyRepository {

	private static final String INSERT = """
			INSERT INTO api_keys (owner_id, name, key_hash, key_prefix, key_last4, scopes, expires_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			RETURNING id, owner_id, name, key_prefix, key_last4, scopes, expires_at,
			          last_used_at, created_at
			""";

	/**
	 * The authentication lookup, on every keyed request: one indexed read on the hash.
	 * It also joins the Owner's auth state, because a request authenticated by a key
	 * needs exactly what a cookie request needs — is this Owner verified — and fetching
	 * it here costs nothing over fetching the key alone.
	 *
	 * <p><b>Expiry is a clause here, not a check afterwards.</b> It costs nothing on a
	 * lookup that was already a single indexed read, and it means no code path can end
	 * up holding an expired key in its hand and forget to look (ADR-0020).
	 */
	private static final String SELECT_BY_HASH = """
			SELECT k.id, k.owner_id, k.scopes, k.last_used_at, o.email_verified
			FROM api_keys k
			JOIN owners o ON o.id = k.owner_id
			WHERE k.key_hash = ?
			  AND k.revoked_at IS NULL
			  AND (k.expires_at IS NULL OR k.expires_at > ?)
			""";

	/**
	 * Filters on {@code revoked_at} alone, so <b>expired keys are still listed</b>
	 * (FR-8.11). A revoked key was ended by someone who knows they ended it; an expired
	 * key ended on its own, and its Owner is the person trying to work out why a script
	 * stopped working.
	 */
	private static final String SELECT_FOR_OWNER = """
			SELECT id, owner_id, name, key_prefix, key_last4, scopes, expires_at,
			       last_used_at, created_at
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
			scopes(rs),
			instant(rs, "expires_at"),
			instant(rs, "last_used_at"),
			rs.getTimestamp("created_at").toInstant());

	/**
	 * A value the CHECK constraint says cannot be there is still read defensively: an
	 * unknown scope is dropped rather than thrown on. Failing authentication because a
	 * future migration added a scope this build has not heard of would take down live
	 * integrations during a rolling deploy, and the safe reading of an unrecognised
	 * permission is "not granted".
	 */
	private static Set<Scope> scopes(ResultSet rs) throws SQLException {
		Array array = rs.getArray("scopes");
		if (array == null) {
			return Set.of();
		}
		return Arrays.stream((String[]) array.getArray())
				.map(Scope::fromWireName)
				.flatMap(Optional::stream)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private final JdbcTemplate jdbc;

	ApiKeyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public ApiKeyRow insert(UUID ownerId, String name, String keyHash, String prefix, String last4,
			Set<Scope> scopes, Instant expiresAt) {

		String[] wireNames = scopes.stream().map(Scope::wireName).toArray(String[]::new);

		return jdbc.query(INSERT, MAPPER, ownerId, name, keyHash, prefix, last4, wireNames,
				expiresAt == null ? null : Timestamp.from(expiresAt)).getFirst();
	}

	/** @param now compared against {@code expires_at} inside the query. */
	public Optional<AuthRow> findByHash(String keyHash, Instant now) {
		List<AuthRow> rows = jdbc.query(SELECT_BY_HASH,
				(rs, n) -> new AuthRow(rs.getLong("id"), rs.getObject("owner_id", UUID.class),
						rs.getBoolean("email_verified"), scopes(rs), instant(rs, "last_used_at")),
				keyHash, Timestamp.from(now));
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
			Set<Scope> scopes, Instant expiresAt, Instant lastUsedAt, Instant createdAt) {}

	public record AuthRow(long keyId, UUID ownerId, boolean emailVerified, Set<Scope> scopes,
			Instant lastUsedAt) {}
}
