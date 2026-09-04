package com.sonanh.urlshortener.identity.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The {@code owners} table. Owned by this module; nothing else reads it.
 */
@Component
public class OwnerRepository {

	/**
	 * {@code ON CONFLICT DO NOTHING} against the case-insensitive email index, for the
	 * same reason link creation uses it: a constraint violation aborts the surrounding
	 * Postgres transaction, so an "insert and catch" cannot be recovered from inside one.
	 * Zero rows means the email is taken.
	 */
	private static final String INSERT = """
			INSERT INTO owners (email, password_hash)
			VALUES (?, ?)
			ON CONFLICT (lower(email)) DO NOTHING
			RETURNING id, email, password_hash, email_verified, token_version, created_at
			""";

	private static final String SELECT_BY_EMAIL = """
			SELECT id, email, password_hash, email_verified, token_version, created_at
			FROM owners
			WHERE lower(email) = lower(?)
			""";

	private static final String SELECT_BY_ID = """
			SELECT id, email, password_hash, email_verified, token_version, created_at
			FROM owners
			WHERE id = ?
			""";

	/** Two columns, not the row: the security layer has no business seeing the rest. */
	private static final String SELECT_AUTH_STATE = """
			SELECT email_verified, token_version FROM owners WHERE id = ?
			""";

	private static final String MARK_VERIFIED = """
			UPDATE owners SET email_verified = true WHERE id = ? AND email_verified = false
			""";

	/**
	 * The password and the version move in one statement, on purpose. As two statements
	 * there is a window where the new password is live and every old session still is
	 * too — which is exactly the window a reset exists to close (FR-1.10).
	 */
	private static final String RESET_PASSWORD = """
			UPDATE owners
			SET password_hash = ?, token_version = token_version + 1
			WHERE id = ?
			""";

	private static final RowMapper<OwnerRow> MAPPER = (rs, n) -> new OwnerRow(
			rs.getObject("id", UUID.class),
			rs.getString("email"),
			rs.getString("password_hash"),
			rs.getBoolean("email_verified"),
			rs.getInt("token_version"),
			rs.getTimestamp("created_at").toInstant());

	private final JdbcTemplate jdbc;

	OwnerRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the new Owner, or empty if the email is already registered. */
	public Optional<OwnerRow> insert(String email, String passwordHash) {
		return first(jdbc.query(INSERT, MAPPER, email, passwordHash));
	}

	public Optional<OwnerRow> findByEmail(String email) {
		return first(jdbc.query(SELECT_BY_EMAIL, MAPPER, email));
	}

	public Optional<OwnerRow> findById(UUID id) {
		return first(jdbc.query(SELECT_BY_ID, MAPPER, id));
	}

	public Optional<AuthStateRow> findAuthState(UUID id) {
		List<AuthStateRow> rows = jdbc.query(SELECT_AUTH_STATE,
				(rs, n) -> new AuthStateRow(rs.getBoolean("email_verified"), rs.getInt("token_version")),
				id);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	/** @return true when this call is the one that flipped it, false if already verified. */
	public boolean markVerified(UUID id) {
		return jdbc.update(MARK_VERIFIED, id) == 1;
	}

	public void resetPassword(UUID id, String passwordHash) {
		jdbc.update(RESET_PASSWORD, passwordHash, id);
	}

	private Optional<OwnerRow> first(List<OwnerRow> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public record OwnerRow(UUID id, String email, String passwordHash, boolean emailVerified,
			int tokenVersion, Instant createdAt) {}

	public record AuthStateRow(boolean emailVerified, int tokenVersion) {}
}
