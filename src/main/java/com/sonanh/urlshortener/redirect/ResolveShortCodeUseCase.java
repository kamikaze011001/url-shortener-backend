package com.sonanh.urlshortener.redirect;

import com.sonanh.urlshortener.analytics.ClickRecorder;
import com.sonanh.urlshortener.links.LinkLookup;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * FR-3: resolve a Short Code, or refuse indistinguishably.
 *
 * <p>Reaches {@code links} and {@code analytics} only through their ports, never
 * through their tables (ADR-0012).
 *
 * <p><b>This use case is deliberately not {@code @Transactional}, unlike every other
 * one.</b> It performs a single indexed read and then hands off to a recorder that
 * owns its own transaction — there is nothing to make atomic, and a transaction here
 * would be actively harmful: recording a Click would join it, so a failed insert would
 * mark it rollback-only and an analytics failure would reach back into the Redirect.
 * A Redirect must never fail because analytics failed (02-nfr.md, ADR-0005).
 *
 * <p>{@link ClickRecorder} enforces its own isolation with REQUIRES_NEW, so the
 * guarantee holds even if a future caller wraps this in a transaction. The annotation's
 * absence documents the intent; the propagation setting is what makes it true.
 */
@Service
public class ResolveShortCodeUseCase {

	private static final Logger log = LoggerFactory.getLogger(ResolveShortCodeUseCase.class);

	private final LinkLookup links;
	private final ClickRecorder clicks;
	private final Clock clock;

	ResolveShortCodeUseCase(LinkLookup links, ClickRecorder clicks, Clock clock) {
		this.links = links;
		this.clicks = clicks;
		this.clock = clock;
	}

	public record Command(String code, String referrer, String userAgent, String country, String ipHash) {}

	/**
	 * @return the Destination, or empty for every other outcome. The caller cannot tell
	 *         "never existed" from "deleted", "disabled" or "expired" — and neither can
	 *         a Visitor, which is the point (ADR-0008).
	 */
	public Optional<String> execute(Command command) {
		Optional<LinkLookup.LinkTarget> found = links.findByCode(command.code());

		if (found.isEmpty()) {
			log.info("link.redirect_missed code={} reason=NOT_FOUND", command.code());
			return Optional.empty();
		}

		LinkLookup.LinkTarget target = found.get();
		if (!target.isRedirectable(clock.instant())) {
			// The real reason goes in the log while the response stays a uniform 404.
			// That is the debuggability cost of ADR-0008, repaid where it is safe to.
			log.info("link.redirect_missed code={} linkId={} reason={}",
					target.code(), target.linkId(), reasonFor(target));
			return Optional.empty();
		}

		// Downstream of the decision, and by contract it cannot throw. A failure here
		// costs a statistic, never a Redirect.
		clicks.record(new ClickRecorder.ClickEvent(
				target.linkId(),
				clock.instant(),
				command.referrer(),
				command.userAgent(),
				command.country(),
				command.ipHash()));

		log.info("link.redirected code={} linkId={}", target.code(), target.linkId());
		return Optional.of(target.destination());
	}

	private String reasonFor(LinkLookup.LinkTarget target) {
		if (target.deleted()) {
			return "DELETED";
		}
		if (!"ACTIVE".equals(target.status())) {
			return "DISABLED";
		}
		return "EXPIRED";
	}
}
