package com.sonanh.urlshortener.analytics.internal.store;

import com.sonanh.urlshortener.analytics.ClickRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0005: Clicks are recorded synchronously, and that is the right choice at this
 * scale. An insert costs well under a millisecond here; an asynchronous pipeline would
 * be optimising a problem that does not exist.
 *
 * <p>The event row and the counter increment go in <b>one</b> transaction, so they
 * cannot disagree. The counter is a denormalisation, not a second source of truth.
 *
 * <p><b>This class is also the thing that breaks first under load.</b> Every concurrent
 * Click on one popular Link contends on a single row in {@code links}. That is row 1 of
 * "what breaks first" in 02-nfr.md, and the replacement — a bounded queue and batch
 * inserts — swaps this class out without the redirect path changing.
 */
@Component
class SyncClickRecorder implements ClickRecorder {

	private static final Logger log = LoggerFactory.getLogger(SyncClickRecorder.class);

	private static final String INSERT_EVENT = """
			INSERT INTO click_events (link_id, occurred_at, referrer, user_agent, country_code, device_type, ip_hash)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INCREMENT = "UPDATE links SET click_count = click_count + 1 WHERE id = ?";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Counter failures;

	SyncClickRecorder(JdbcTemplate jdbc, PlatformTransactionManager txManager, MeterRegistry meters) {
		this.jdbc = jdbc;

		// REQUIRES_NEW, not the default REQUIRED. Recording must be isolated from
		// whatever transaction the caller is in — if it joined one, a failed insert
		// would mark the caller's transaction rollback-only and an analytics failure
		// would reach back into the Redirect. That is precisely what ADR-0005 forbids,
		// and REQUIRED would make the guarantee depend on every caller staying
		// non-transactional forever.
		this.tx = new TransactionTemplate(txManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		this.failures = Counter.builder("urlshortener.clicks.record.failures")
				.description("Clicks that could not be recorded. Redirects were still served.")
				.register(meters);
	}

	/**
	 * Never throws. A Redirect must not fail because analytics failed — that is a
	 * correctness property from 02-nfr.md, not a performance concern, which is why the
	 * catch is deliberately broad and the failure is counted rather than propagated.
	 */
	@Override
	public void record(ClickEvent event) {
		try {
			tx.executeWithoutResult(status -> {
				jdbc.update(INSERT_EVENT,
						event.linkId(),
						Timestamp.from(event.occurredAt()),
						event.referrer(),
						event.userAgent(),
						event.country(),
						deviceTypeOf(event.userAgent()),
						event.ipHash());
				jdbc.update(INCREMENT, event.linkId());
			});
		}
		catch (RuntimeException ex) {
			failures.increment();
			log.warn("click.record_failed linkId={} reason={}", event.linkId(), ex.toString());
		}
	}

	/**
	 * Crude on purpose. A real user-agent parser is a dependency and a maintenance
	 * burden for a field that only ever groups a bar chart.
	 */
	private String deviceTypeOf(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return "UNKNOWN";
		}
		String ua = userAgent.toLowerCase();
		if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider") || ua.contains("curl")) {
			return "BOT";
		}
		if (ua.contains("ipad") || ua.contains("tablet")) {
			return "TABLET";
		}
		if (ua.contains("mobi") || ua.contains("android") || ua.contains("iphone")) {
			return "MOBILE";
		}
		return "DESKTOP";
	}
}
