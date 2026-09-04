package com.sonanh.urlshortener.links.usecase;

import com.sonanh.urlshortener.links.store.LinkHistoryRepository;
import com.sonanh.urlshortener.links.store.LinkRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-4.9: an Owner reads the Destination history of their own Link.
 *
 * <p>The record has always been written — {@code UpdateLinkUseCase} has appended to it
 * since ADR-0009 — and until now nothing could read it. That ADR defends a mutable
 * Destination on the grounds that every change is recorded with who and when, so the
 * argument was resting on a table no user could see. This use case is what turns that
 * from a claim into a feature.
 */
@Service
public class GetLinkHistoryUseCase {

	private final LinkRepository links;
	private final LinkHistoryRepository history;

	GetLinkHistoryUseCase(LinkRepository links, LinkHistoryRepository history) {
		this.links = links;
		this.history = history;
	}

	public record Command(UUID ownerId, long linkId) {}

	public record Change(long id, String oldDestination, String newDestination,
			UUID changedBy, Instant changedAt) {}

	/**
	 * Ownership is checked against the Link, not the history rows, because the history
	 * table has no owner column — the Link is what is owned. Another Owner's Link is
	 * NOT_FOUND rather than FORBIDDEN, as everywhere else (ADR-0008).
	 */
	@Transactional(readOnly = true)
	public List<Change> execute(Command command) {
		links.findForOwner(command.linkId(), command.ownerId())
				.orElseThrow(() -> ApiException.notFound("No such link."));

		return history.findForLink(command.linkId()).stream()
				.map(row -> new Change(row.id(), row.oldDestination(), row.newDestination(),
						row.changedBy(), row.changedAt()))
				.toList();
	}
}
