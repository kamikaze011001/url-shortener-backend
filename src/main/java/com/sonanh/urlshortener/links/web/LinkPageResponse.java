package com.sonanh.urlshortener.links.web;

import com.sonanh.urlshortener.links.usecase.ListLinksUseCase;
import java.util.List;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record LinkPageResponse(
		List<LinkResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages
) {

	static LinkPageResponse from(ListLinksUseCase.Result result) {
		return new LinkPageResponse(
				result.content().stream().map(LinkResponse::from).toList(),
				result.page(),
				result.size(),
				result.totalElements(),
				result.totalPages());
	}
}
