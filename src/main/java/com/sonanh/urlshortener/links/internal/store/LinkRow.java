package com.sonanh.urlshortener.links.internal.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.RowMapper;

/** A row of {@code links}, as the module reads it internally. */
public record LinkRow(
		long id,
		String code,
		String destination,
		String status,
		boolean customAlias,
		long clickCount,
		Instant expiresAt,
		Instant createdAt,
		Instant updatedAt
) {

	public static final RowMapper<LinkRow> MAPPER = (ResultSet rs, int rowNum) -> new LinkRow(
			rs.getLong("id"),
			rs.getString("code"),
			rs.getString("destination"),
			rs.getString("status"),
			rs.getBoolean("is_custom_alias"),
			rs.getLong("click_count"),
			instant(rs.getTimestamp("expires_at")),
			instant(rs.getTimestamp("created_at")),
			instant(rs.getTimestamp("updated_at"))
	);

	private static Instant instant(Timestamp ts) throws SQLException {
		return ts == null ? null : ts.toInstant();
	}
}
