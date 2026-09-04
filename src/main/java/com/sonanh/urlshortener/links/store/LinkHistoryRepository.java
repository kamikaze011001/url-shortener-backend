package com.sonanh.urlshortener.links.store;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Reads {@code link_destination_history}, which {@link LinkRepository} writes.
 *
 * <p>A separate class because the two have nothing to say to each other: this one only
 * reads, and the write is a side effect of updating a Link. Folding the read into
 * LinkRepository would grow the class that already owns the busiest table in the system.
 */
@Component
public class LinkHistoryRepository {

	/**
	 * Newest first, and capped.
	 *
	 * <p>The cap is not paging. A Link with more than twenty Destination changes is
	 * either a mistake or something worth investigating rather than scrolling, and an
	 * unbounded read here would let one pathological row set the response size for
	 * everyone. If a use for the full record appears, it wants an export, not a longer
	 * page.
	 */
	private static final String SELECT = """
			SELECT id, old_destination, new_destination, changed_by, changed_at
			FROM link_destination_history
			WHERE link_id = ?
			ORDER BY changed_at DESC
			LIMIT 20
			""";

	private static final RowMapper<ChangeRow> MAPPER = (rs, n) -> new ChangeRow(
			rs.getLong("id"),
			rs.getString("old_destination"),
			rs.getString("new_destination"),
			rs.getObject("changed_by", UUID.class),
			rs.getTimestamp("changed_at").toInstant());

	private final JdbcTemplate jdbc;

	LinkHistoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Takes a Link id that the caller has <b>already</b> confirmed belongs to the Owner.
	 * There is no owner column on this table — the ownership lives on the Link — so this
	 * method cannot check it and does not pretend to.
	 */
	public List<ChangeRow> findForLink(long linkId) {
		return jdbc.query(SELECT, MAPPER, linkId);
	}

	public record ChangeRow(long id, String oldDestination, String newDestination,
			UUID changedBy, Instant changedAt) {}
}
