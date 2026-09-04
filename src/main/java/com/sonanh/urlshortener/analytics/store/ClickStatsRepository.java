package com.sonanh.urlshortener.analytics.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code click_events}, which this module owns.
 *
 * <p><b>Four queries, not one.</b> A single pass with {@code GROUPING SETS} would return
 * all four breakdowns together, and is the obvious optimisation — but it returns a
 * ragged result set that has to be demultiplexed by inspecting which grouping columns
 * are null, and that code is considerably harder to read than four named methods. At
 * this scale the four round trips cost less than a millisecond in total. The moment
 * that stops being true, this is the class to change, and nothing outside it moves.
 *
 * <p><b>Days are UTC days.</b> {@code occurred_at} is a {@code timestamptz}, so grouping
 * by date requires choosing a zone, and the choice is visible to the user: an Owner in
 * GMT+7 sees a click made at 06:00 local land on the previous day. UTC is chosen because
 * it is the same for every Owner and for every reader of the database — a per-Owner zone
 * would mean the same Link reports different daily numbers to different people, which is
 * worse than an offset everybody shares. See 02-nfr.md.
 */
@Repository
public class ClickStatsRepository {

	private static final String DAILY = """
			SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, count(*) AS clicks
			FROM click_events
			WHERE link_id = ? AND occurred_at >= ? AND occurred_at < ?
			GROUP BY day
			ORDER BY day
			""";

	/**
	 * The label queries share a shape, so they share a template. Only the grouping
	 * column changes, and it is interpolated from a constant in this file rather than
	 * from anything a caller supplies — the parameters that come from outside are still
	 * bound.
	 */
	private static final String BY_LABEL = """
			SELECT %s AS label, count(*) AS clicks
			FROM click_events
			WHERE link_id = ? AND occurred_at >= ? AND occurred_at < ?
			GROUP BY label
			ORDER BY clicks DESC
			""";

	private final JdbcTemplate jdbc;

	ClickStatsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record DayCount(LocalDate date, long clicks) {}

	/** {@code label} is null for referrers, which means the Visitor arrived directly. */
	public record LabelCount(String label, long clicks) {}

	public List<DayCount> daily(long linkId, Instant from, Instant toExclusive) {
		return jdbc.query(DAILY,
				(rs, row) -> new DayCount(rs.getObject("day", LocalDate.class), rs.getLong("clicks")),
				linkId, Timestamp.from(from), Timestamp.from(toExclusive));
	}

	public List<LabelCount> byCountry(long linkId, Instant from, Instant toExclusive) {
		return byLabel("country_code", linkId, from, toExclusive);
	}

	public List<LabelCount> byReferrer(long linkId, Instant from, Instant toExclusive) {
		return byLabel("referrer", linkId, from, toExclusive);
	}

	public List<LabelCount> byDevice(long linkId, Instant from, Instant toExclusive) {
		return byLabel("device_type", linkId, from, toExclusive);
	}

	private List<LabelCount> byLabel(String column, long linkId, Instant from, Instant toExclusive) {
		return jdbc.query(BY_LABEL.formatted(column),
				(rs, row) -> new LabelCount(rs.getString("label"), rs.getLong("clicks")),
				linkId, Timestamp.from(from), Timestamp.from(toExclusive));
	}
}
