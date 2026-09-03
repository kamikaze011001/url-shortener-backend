package com.sonanh.urlshortener.identity.store;

import java.sql.Timestamp;
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
			RETURNING id, email, password_hash, created_at
			""";

	private static final String SELECT_BY_EMAIL = """
			SELECT id, email, password_hash, created_at
			FROM owners
			WHERE lower(email) = lower(?)
			""";

	private static final String SELECT_BY_ID = """
			SELECT id, email, password_hash, created_at
			FROM owners
			WHERE id = ?
			""";

	private static final RowMapper<OwnerRow> MAPPER = (rs, n) -> new OwnerRow(
			rs.getObject("id", UUID.class),
			rs.getString("email"),
			rs.getString("password_hash"),
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

	private Optional<OwnerRow> first(List<OwnerRow> rows) {
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public record OwnerRow(UUID id, String email, String passwordHash, Instant createdAt) {}
}
