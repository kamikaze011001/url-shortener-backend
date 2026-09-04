package com.sonanh.urlshortener.analytics.usecase;

import com.sonanh.urlshortener.analytics.domain.ReferrerHost;
import com.sonanh.urlshortener.analytics.store.ClickStatsRepository;
import com.sonanh.urlshortener.links.port.LinkOwnership;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-5: an Owner reads the Click statistics for one of their own Links. */
@Service
public class GetLinkStatsUseCase {

	/** Matches the contract's stated default: "Defaults to 30 days ago." */
	private static final int DEFAULT_WINDOW_DAYS = 30;

	/**
	 * A year and a day. The response carries one entry per day in the range, so an
	 * unbounded window lets a caller ask for a megabyte of zeroes with one query string.
	 */
	private static final int MAX_WINDOW_DAYS = 366;

	/**
	 * Countries and referrers have unbounded cardinality, so the tail is truncated. Ten
	 * is what a bar chart can show without becoming a list; the totals are unaffected,
	 * because {@code totalClicks} is counted separately rather than summed from these.
	 */
	private static final int TOP_LABELS = 10;

	private final ClickStatsRepository clicks;
	private final LinkOwnership links;
	private final Clock clock;

	GetLinkStatsUseCase(ClickStatsRepository clicks, LinkOwnership links, Clock clock) {
		this.clicks = clicks;
		this.links = links;
		this.clock = clock;
	}

	public record Command(UUID ownerId, long linkId, LocalDate from, LocalDate to) {}

	public record Result(
			long linkId,
			long totalClicks,
			LocalDate from,
			LocalDate to,
			List<DayPoint> daily,
			List<LabelPoint> byCountry,
			List<LabelPoint> byReferrer,
			List<LabelPoint> byDevice
	) {}

	public record DayPoint(LocalDate date, long clicks) {}

	/** {@code label} is null for the referrer breakdown and means the Visitor came directly. */
	public record LabelPoint(String label, long clicks) {}

	/**
	 * Read-only, and one transaction rather than four: the four queries make up a single
	 * picture, and without a shared snapshot a Click landing between them could be
	 * counted in the device breakdown but not the daily one, producing a chart whose
	 * totals disagree with themselves.
	 */
	@Transactional(readOnly = true)
	public Result execute(Command command) {
		// Ownership first, before any work. Someone else's Link is NOT_FOUND rather than
		// FORBIDDEN — a 403 would confirm it exists (ADR-0008).
		if (!links.isOwnedBy(command.linkId(), command.ownerId())) {
			throw ApiException.notFound("No such link.");
		}

		LocalDate to = command.to() != null ? command.to() : LocalDate.now(clock.withZone(ZoneOffset.UTC));
		LocalDate from = command.from() != null ? command.from() : to.minusDays(DEFAULT_WINDOW_DAYS);
		validate(from, to);

		Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
		// The range is inclusive of `to`, so the exclusive upper bound is the following
		// midnight. Expressed as `< next day` rather than `<= end of day` because there
		// is no last instant of a day to write down.
		Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

		List<DayPoint> daily = fillGaps(clicks.daily(command.linkId(), start, end), from, to);

		return new Result(
				command.linkId(),
				daily.stream().mapToLong(DayPoint::clicks).sum(),
				from,
				to,
				daily,
				top(clicks.byCountry(command.linkId(), start, end)),
				topReferrers(clicks.byReferrer(command.linkId(), start, end)),
				top(clicks.byDevice(command.linkId(), start, end)));
	}

	private void validate(LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			throw new ApiException(ProblemCode.VALIDATION_FAILED, "'from' must not be after 'to'.");
		}
		if (from.plusDays(MAX_WINDOW_DAYS).isBefore(to)) {
			throw new ApiException(ProblemCode.VALIDATION_FAILED,
					"The range must not exceed " + MAX_WINDOW_DAYS + " days.");
		}
	}

	/**
	 * A day with no Clicks is absent from the query result, but it is not missing data —
	 * it is a zero, and a chart that omits it draws a line straight from Monday to
	 * Friday as though Tuesday never happened. The series is filled here rather than in
	 * the browser so that every client draws the same shape.
	 */
	private List<DayPoint> fillGaps(List<ClickStatsRepository.DayCount> counted, LocalDate from, LocalDate to) {
		Map<LocalDate, Long> byDate = new LinkedHashMap<>();
		counted.forEach(row -> byDate.put(row.date(), row.clicks()));

		List<DayPoint> series = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			series.add(new DayPoint(day, byDate.getOrDefault(day, 0L)));
		}
		return series;
	}

	private List<LabelPoint> top(List<ClickStatsRepository.LabelCount> counted) {
		return counted.stream()
				.limit(TOP_LABELS)
				.map(row -> new LabelPoint(row.label(), row.clicks()))
				.toList();
	}

	/**
	 * Referrers are folded to their host before truncation, not after. Truncating first
	 * would rank a hundred distinct paths from one site as a hundred single visits and
	 * drop the site that actually sent the traffic.
	 */
	private List<LabelPoint> topReferrers(List<ClickStatsRepository.LabelCount> counted) {
		Map<String, Long> byHost = new LinkedHashMap<>();
		for (ClickStatsRepository.LabelCount row : counted) {
			byHost.merge(ReferrerHost.of(row.label()), row.clicks(), Long::sum);
		}

		return byHost.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.limit(TOP_LABELS)
				.map(entry -> new LabelPoint(entry.getKey(), entry.getValue()))
				.toList();
	}
}
