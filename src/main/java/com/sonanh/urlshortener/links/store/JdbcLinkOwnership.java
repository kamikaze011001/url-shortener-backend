package com.sonanh.urlshortener.links.store;

import com.sonanh.urlshortener.links.port.LinkOwnership;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code links} table is owned by this module, so the ownership check lives here
 * even though the only caller is {@code analytics}. Letting that module query
 * {@code links} directly would be a coupling the boundary test cannot see.
 */
@Component
class JdbcLinkOwnership implements LinkOwnership {

	/**
	 * {@code EXISTS} rather than a count or a row fetch: the answer is one boolean, and
	 * Postgres can stop at the first match on the primary key.
	 *
	 * <p>{@code deleted_at IS NULL} is part of the predicate, not a separate check. A
	 * deleted Link keeps its row forever so that its code is never reused, which means
	 * every read path has to exclude it explicitly — forgetting to is how a deleted
	 * Link comes back to life in exactly one screen.
	 */
	private static final String IS_OWNED_BY = """
			SELECT EXISTS (
			    SELECT 1 FROM links
			    WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
			)
			""";

	private final JdbcTemplate jdbc;

	JdbcLinkOwnership(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean isOwnedBy(long linkId, UUID ownerId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(IS_OWNED_BY, Boolean.class, linkId, ownerId));
	}
}
