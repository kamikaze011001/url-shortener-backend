package com.sonanh.urlshortener.analytics.web;

import com.sonanh.urlshortener.analytics.usecase.GetLinkStatsUseCase;
import com.sonanh.urlshortener.shared.security.CurrentOwner;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statistics for one Link.
 *
 * <p>The path sits under {@code /links} while the code sits in {@code analytics}, and
 * that is deliberate: the URL describes the resource an Owner is asking about, and the
 * package describes which module owns the data. Moving this class into {@code links} to
 * make the two match would put {@code click_events} queries in a module that does not
 * own that table, which is the coupling the boundary exists to prevent.
 */
@RestController
class StatsController {

	private final GetLinkStatsUseCase getStats;
	private final CurrentOwner currentOwner;

	StatsController(GetLinkStatsUseCase getStats, CurrentOwner currentOwner) {
		this.getStats = getStats;
		this.currentOwner = currentOwner;
	}

	/**
	 * {@code from} and {@code to} are optional; the use case fills the default window,
	 * because "the last thirty days" is a business rule and not a parsing concern.
	 *
	 * <p>A non-numeric {@code id} or an unparseable date is rejected by Spring before
	 * this method runs and surfaces as a 400, which is the same answer this would give.
	 */
	@GetMapping("/api/v1/links/{id}/stats")
	LinkStatsResponse stats(
			@PathVariable long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

		var command = new GetLinkStatsUseCase.Command(currentOwner.id(), id, from, to);
		return LinkStatsResponse.from(getStats.execute(command));
	}
}
