package com.sonanh.urlshortener.links.usecase;

import com.sonanh.urlshortener.links.store.LinkRepository;
import com.sonanh.urlshortener.shared.config.AppProperties;
import com.sonanh.urlshortener.shared.error.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-4.8: an Owner reads one of their own Links. */
@Service
public class GetLinkUseCase {

	private final LinkRepository links;
	private final AppProperties properties;
	private final Clock clock;

	GetLinkUseCase(LinkRepository links, AppProperties properties, Clock clock) {
		this.links = links;
		this.properties = properties;
		this.clock = clock;
	}

	public record Command(UUID ownerId, long linkId) {}

	/**
	 * Another Owner's Link is NOT_FOUND, not FORBIDDEN. A 403 would confirm the Link
	 * exists, which is the same leak the uniform 404 on the redirect path closes
	 * (ADR-0008).
	 */
	@Transactional(readOnly = true)
	public LinkView execute(Command command) {
		return links.findForOwner(command.linkId(), command.ownerId())
				.map(row -> LinkView.of(row, properties.shortUrlFor(row.code()), clock.instant()))
				.orElseThrow(() -> ApiException.notFound("No such link."));
	}
}
