package com.sonanh.urlshortener.links.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Inserts a Link, reporting a taken code as an <em>absent result</em> rather than an
 * exception.
 *
 * <p>This is why the insert is {@code ON CONFLICT DO NOTHING} rather than a plain
 * insert with a caught {@code DataIntegrityViolationException}: <b>a constraint
 * violation aborts the surrounding Postgres transaction</b>. Every statement after it
 * fails with "current transaction is aborted", so a retry loop inside one transaction
 * cannot work. Catching the exception in Java does not un-abort the database.
 *
 * <p>{@code ON CONFLICT DO NOTHING} raises nothing, leaves the transaction healthy, and
 * returns zero rows — so the caller can simply generate another code and try again
 * (ADR-0002).
 */
@Component
public class LinkWriter {

	private static final String INSERT = """
			INSERT INTO links (code, destination, owner_id, is_custom_alias, expires_at)
			VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (code) DO NOTHING
			RETURNING id, code, destination, status, is_custom_alias,
			          click_count, expires_at, created_at, updated_at
			""";

	private final JdbcTemplate jdbc;

	LinkWriter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the inserted Link, or empty if the code was already taken. */
	public Optional<LinkRow> insert(String code, String destination, UUID ownerId,
			boolean customAlias, Instant expiresAt) {

		List<LinkRow> rows = jdbc.query(INSERT, LinkRow.MAPPER,
				code, destination, ownerId, customAlias,
				expiresAt == null ? null : Timestamp.from(expiresAt));

		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}
}
