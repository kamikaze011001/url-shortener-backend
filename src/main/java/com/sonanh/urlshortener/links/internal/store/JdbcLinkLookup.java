package com.sonanh.urlshortener.links.internal.store;

import com.sonanh.urlshortener.links.LinkLookup;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The redirect path's read, and the only implementation of {@link LinkLookup}.
 *
 * <p>Plain JDBC rather than JPA on purpose: this is a single row fetched by unique
 * index, and an entity manager, a persistence context and dirty checking are pure
 * overhead on the one path with a 20 ms p99 budget.
 *
 * <p>It selects the five columns a Redirect needs and nothing else. In particular it
 * does <b>not</b> read {@code click_count}, which is why the result is safe to cache
 * (ADR-0004).
 */
@Component
class JdbcLinkLookup implements LinkLookup {

	private static final String SELECT_BY_CODE = """
			SELECT id, code, destination, status, expires_at, deleted_at
			FROM links
			WHERE code = ?
			""";

	private final JdbcTemplate jdbc;

	JdbcLinkLookup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<LinkTarget> findByCode(String code) {
		List<LinkTarget> rows = jdbc.query(SELECT_BY_CODE, (rs, n) -> {
			Timestamp expires = rs.getTimestamp("expires_at");
			return new LinkTarget(
					rs.getLong("id"),
					rs.getString("code"),
					rs.getString("destination"),
					rs.getString("status"),
					expires == null ? null : expires.toInstant(),
					rs.getTimestamp("deleted_at") != null);
		}, code);

		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}
}
