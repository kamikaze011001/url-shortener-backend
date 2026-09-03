package com.sonanh.urlshortener.links.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the {@code links} table. This module owns it; nothing else touches it.
 *
 * <p>Every query is scoped by {@code owner_id} and {@code deleted_at IS NULL} in the
 * SQL itself rather than filtered afterwards, so "can this Owner see this Link?" cannot
 * be forgotten at a call site. A Link belonging to someone else is simply not found,
 * which is what makes the 404-not-403 rule (ADR-0008) hold by construction.
 */
@Component
public class LinkRepository {

	private static final String COLUMNS = """
			id, code, destination, status, is_custom_alias,
			click_count, expires_at, created_at, updated_at
			""";

	private final JdbcTemplate jdbc;

	LinkRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static final String INSERT = """
			INSERT INTO links (code, destination, owner_id, is_custom_alias, expires_at)
			VALUES (?, ?, ?, ?, ?)
			ON CONFLICT (code) DO NOTHING
			RETURNING id, code, destination, status, is_custom_alias,
			          click_count, expires_at, created_at, updated_at
			""";

	/**
	 * Inserts a Link, reporting a taken code as an <em>absent result</em> rather than an
	 * exception.
	 *
	 * <p>{@code ON CONFLICT DO NOTHING} rather than a caught
	 * {@code DataIntegrityViolationException}, because <b>a constraint violation aborts
	 * the surrounding Postgres transaction</b>. Every statement after it fails with
	 * "current transaction is aborted", so a retry loop inside one transaction cannot
	 * work — catching the exception in Java does not un-abort the database. ON CONFLICT
	 * raises nothing, returns zero rows, and leaves the transaction healthy (ADR-0002).
	 *
	 * @return the inserted Link, or empty if the code was already taken.
	 */
	public Optional<LinkRow> insert(String code, String destination, UUID ownerId,
			boolean customAlias, Instant expiresAt) {

		List<LinkRow> rows = jdbc.query(INSERT, LinkRow.MAPPER, code, destination, ownerId,
				customAlias, expiresAt == null ? null : Timestamp.from(expiresAt));

		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public Optional<LinkRow> findForOwner(long id, UUID ownerId) {
		List<LinkRow> rows = jdbc.query(
				"SELECT " + COLUMNS + " FROM links WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
				LinkRow.MAPPER, id, ownerId);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public List<LinkRow> list(UUID ownerId, String search, String status, int page, int size) {
		Filter filter = new Filter(ownerId, search, status);
		List<Object> args = new ArrayList<>(filter.args());
		args.add(size);
		args.add(page * size);

		return jdbc.query("SELECT " + COLUMNS + " FROM links WHERE " + filter.sql()
				+ " ORDER BY created_at DESC LIMIT ? OFFSET ?", LinkRow.MAPPER, args.toArray());
	}

	public long count(UUID ownerId, String search, String status) {
		Filter filter = new Filter(ownerId, search, status);
		Long total = jdbc.queryForObject("SELECT count(*) FROM links WHERE " + filter.sql(),
				Long.class, filter.args().toArray());
		return total == null ? 0 : total;
	}

	/** @return the updated row, or empty if it is not this Owner's or already deleted. */
	public Optional<LinkRow> update(long id, UUID ownerId, String destination, String status,
			boolean changeExpiry, Instant expiresAt) {

		List<String> sets = new ArrayList<>();
		List<Object> args = new ArrayList<>();

		if (destination != null) {
			sets.add("destination = ?");
			args.add(destination);
		}
		if (status != null) {
			sets.add("status = ?");
			args.add(status);
		}
		if (changeExpiry) {
			// Separate flag rather than a null check: null here means "clear the
			// expiry", which is a different instruction from "leave it alone".
			sets.add("expires_at = ?");
			args.add(expiresAt == null ? null : Timestamp.from(expiresAt));
		}
		if (sets.isEmpty()) {
			return findForOwner(id, ownerId);
		}

		sets.add("updated_at = now()");
		args.add(id);
		args.add(ownerId);

		List<LinkRow> rows = jdbc.query("UPDATE links SET " + String.join(", ", sets)
				+ " WHERE id = ? AND owner_id = ? AND deleted_at IS NULL RETURNING " + COLUMNS,
				LinkRow.MAPPER, args.toArray());

		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	/**
	 * Soft delete. The row stays and keeps its code forever — releasing it would let a
	 * stranger claim a code already printed on someone's poster (ADR-0008).
	 */
	public boolean softDelete(long id, UUID ownerId) {
		return jdbc.update(
				"UPDATE links SET deleted_at = now(), updated_at = now() "
						+ "WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
				id, ownerId) > 0;
	}

	public void recordDestinationChange(long linkId, String from, String to, UUID changedBy) {
		jdbc.update("""
				INSERT INTO link_destination_history (link_id, old_destination, new_destination, changed_by)
				VALUES (?, ?, ?, ?)
				""", linkId, from, to, changedBy);
	}

	/**
	 * Builds the shared WHERE clause for listing and counting, so a filter can never be
	 * applied to one and not the other — which would make page counts lie.
	 */
	private record Filter(UUID ownerId, String search, String status) {

		String sql() {
			StringBuilder sql = new StringBuilder("owner_id = ? AND deleted_at IS NULL");
			if (hasSearch()) {
				sql.append(" AND (code ILIKE ? OR destination ILIKE ?)");
			}
			if (status != null) {
				sql.append(switch (status) {
					// EXPIRED and DISABLED are not the same thing, and a Link can be
					// both. Filtering on either asks about that property alone.
					case "ACTIVE" -> " AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > now())";
					case "DISABLED" -> " AND status = 'DISABLED'";
					case "EXPIRED" -> " AND expires_at IS NOT NULL AND expires_at <= now()";
					default -> "";
				});
			}
			return sql.toString();
		}

		List<Object> args() {
			List<Object> args = new ArrayList<>();
			args.add(ownerId);
			if (hasSearch()) {
				String pattern = "%" + search.trim() + "%";
				args.add(pattern);
				args.add(pattern);
			}
			return args;
		}

		private boolean hasSearch() {
			return search != null && !search.isBlank();
		}
	}
}
