package com.sonanh.urlshortener.links.usecase;

import com.sonanh.urlshortener.links.store.LinkRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-4.6: an Owner deletes a Link. The Short Code is never released. */
@Service
public class DeleteLinkUseCase {

	private static final Logger log = LoggerFactory.getLogger(DeleteLinkUseCase.class);

	private final LinkRepository links;

	DeleteLinkUseCase(LinkRepository links) {
		this.links = links;
	}

	public record Command(UUID ownerId, long linkId) {}

	@Transactional
	public void execute(Command command) {
		if (!links.softDelete(command.linkId(), command.ownerId())) {
			throw ApiException.notFound("No such link.");
		}
		log.info("link.deleted linkId={} ownerId={}", command.linkId(), command.ownerId());
	}
}
