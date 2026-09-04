package com.sonanh.urlshortener.analytics.web;

import com.sonanh.urlshortener.analytics.usecase.GetLinkStatsUseCase;
import java.time.LocalDate;
import java.util.List;

/**
 * The wire shape from {@code contracts/openapi.yaml}. Separate from the use case's
 * {@code Result} so that renaming a field here is a contract change and renaming one
 * there is not — the two agreeing today is not a reason to make them the same type.
 */
record LinkStatsResponse(
		String linkId,
		long totalClicks,
		LocalDate from,
		LocalDate to,
		List<DailyPoint> daily,
		List<CountryPoint> byCountry,
		List<ReferrerPoint> byReferrer,
		List<DevicePoint> byDevice
) {

	record DailyPoint(LocalDate date, long clicks) {}

	record CountryPoint(String countryCode, long clicks) {}

	/** {@code referrer} is null when the Visitor arrived directly. */
	record ReferrerPoint(String referrer, long clicks) {}

	record DevicePoint(String deviceType, long clicks) {}

	static LinkStatsResponse from(GetLinkStatsUseCase.Result result) {
		return new LinkStatsResponse(
				// A string, because the contract types every id as a string and the
				// frontend compares them without knowing they are numbers here.
				String.valueOf(result.linkId()),
				result.totalClicks(),
				result.from(),
				result.to(),
				result.daily().stream().map(p -> new DailyPoint(p.date(), p.clicks())).toList(),
				result.byCountry().stream().map(p -> new CountryPoint(p.label(), p.clicks())).toList(),
				result.byReferrer().stream().map(p -> new ReferrerPoint(p.label(), p.clicks())).toList(),
				result.byDevice().stream().map(p -> new DevicePoint(p.label(), p.clicks())).toList());
	}
}
