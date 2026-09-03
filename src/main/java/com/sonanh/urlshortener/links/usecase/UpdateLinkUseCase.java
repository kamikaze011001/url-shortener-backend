package com.sonanh.urlshortener.links.usecase;

import com.sonanh.urlshortener.links.domain.DestinationPolicy;
import com.sonanh.urlshortener.links.store.LinkRepository;
import com.sonanh.urlshortener.links.store.LinkRow;
import com.sonanh.urlshortener.shared.config.AppProperties;
import com.sonanh.urlshortener.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-4.3 to FR-4.5: an Owner changes a Link's Destination, status or expiry.
 *
 * <p>The Short Code is not changeable and never will be. Renaming would release the old
 * string back into the Code Namespace for someone else to claim — the same hazard as
 * recycling a deleted code (ADR-0008).
 */
@Service
public class UpdateLinkUseCase {

	private static final Logger log = LoggerFactory.getLogger(UpdateLinkUseCase.class);

	private final LinkRepository links;
	private final DestinationPolicy destinations;
	private final AppProperties properties;
	private final Clock clock;

	UpdateLinkUseCase(LinkRepository links, DestinationPolicy destinations,
			AppProperties properties, Clock clock) {
		this.links = links;
		this.destinations = destinations;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @param destination  null leaves the Destination alone.
	 * @param status       null leaves the status alone.
	 * @param changeExpiry whether {@code expiresAt} should be written at all. Separate
	 *                     from the value because "clear the expiry" and "leave the
	 *                     expiry alone" are different instructions that would otherwise
	 *                     both arrive as null.
	 */
	public record Command(
			UUID ownerId,
			long linkId,
			String destination,
			String status,
			boolean changeExpiry,
			Instant expiresAt
	) {}

	@Transactional
	public LinkView execute(Command command) {
		LinkRow current = links.findForOwner(command.linkId(), command.ownerId())
				.orElseThrow(() -> ApiException.notFound("No such link."));

		// The same gate creation uses. Without it, an attacker creates a Link to a
		// harmless URL and then PATCHes it to http://192.168.1.1/admin — a security
		// rule applied at one of two entry points is not applied.
		if (command.destination() != null) {
			destinations.check(command.destination());
		}

		LinkRow updated = links.update(command.linkId(), command.ownerId(),
						command.destination(), command.status(),
						command.changeExpiry(), command.expiresAt())
				.orElseThrow(() -> ApiException.notFound("No such link."));

		if (command.destination() != null && !command.destination().equals(current.destination())) {
			// Written in the same transaction as the change itself. An audit trail that
			// can be missing entries is not one — and it is what makes a mutable
			// Destination defensible rather than a bait-and-switch loophole (ADR-0009).
			links.recordDestinationChange(current.id(), current.destination(),
					command.destination(), command.ownerId());

			log.info("link.destination_changed linkId={} from={} to={}",
					current.id(), current.destination(), command.destination());
		}

		if (command.status() != null && !command.status().equals(current.status())) {
			log.info("link.status_changed linkId={} from={} to={}",
					current.id(), current.status(), command.status());
		}

		return LinkView.of(updated, properties.shortUrlFor(updated.code()), clock.instant());
	}
}
