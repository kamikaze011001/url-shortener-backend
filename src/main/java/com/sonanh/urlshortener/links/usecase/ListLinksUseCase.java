package com.sonanh.urlshortener.links.usecase;

import com.sonanh.urlshortener.links.store.LinkRepository;
import com.sonanh.urlshortener.shared.config.AppProperties;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-4.1 and FR-4.2: an Owner lists and searches their own Links, newest first. */
@Service
public class ListLinksUseCase {

	private final LinkRepository links;
	private final AppProperties properties;
	private final Clock clock;

	ListLinksUseCase(LinkRepository links, AppProperties properties, Clock clock) {
		this.links = links;
		this.properties = properties;
		this.clock = clock;
	}

	public record Command(UUID ownerId, String search, String status, int page, int size) {}

	public record Result(List<LinkView> content, int page, int size, long totalElements, int totalPages) {}

	/**
	 * Page-based rather than cursor-based: the dashboard needs page numbers, and at this
	 * scale a COUNT is free. Cursor pagination is the change to make if listing ever
	 * gets slow, and it is a contract change when it happens.
	 */
	@Transactional(readOnly = true)
	public Result execute(Command command) {
		var now = clock.instant();

		List<LinkView> content = links
				.list(command.ownerId(), command.search(), command.status(), command.page(), command.size())
				.stream()
				.map(row -> LinkView.of(row, properties.shortUrlFor(row.code()), now))
				.toList();

		long total = links.count(command.ownerId(), command.search(), command.status());
		int totalPages = command.size() == 0 ? 0 : (int) Math.ceil((double) total / command.size());

		return new Result(content, command.page(), command.size(), total, totalPages);
	}
}
