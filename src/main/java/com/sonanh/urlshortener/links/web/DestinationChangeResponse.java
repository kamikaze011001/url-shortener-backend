package com.sonanh.urlshortener.links.web;

import com.sonanh.urlshortener.links.usecase.GetLinkHistoryUseCase;
import java.time.Instant;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record DestinationChangeResponse(String id, String oldDestination, String newDestination,
		Instant changedAt) {

	/**
	 * {@code changedBy} is not on the wire. Today it is always the Owner reading it, so
	 * returning their own id would be noise — and it is a UUID no client could render as
	 * a person anyway. It becomes worth exposing when more than one person can change a
	 * Link, which is the teams feature the non-goals table defers.
	 */
	static DestinationChangeResponse from(GetLinkHistoryUseCase.Change change) {
		return new DestinationChangeResponse(String.valueOf(change.id()), change.oldDestination(),
				change.newDestination(), change.changedAt());
	}
}
