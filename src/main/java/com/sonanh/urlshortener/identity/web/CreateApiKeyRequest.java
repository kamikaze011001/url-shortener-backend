package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.security.Scope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Wire shape, fixed by {@code contracts/openapi.yaml}. */
record CreateApiKeyRequest(

		@NotBlank(message = "must not be blank")
		@Size(max = 64, message = "must be at most 64 characters")
		String name,

		// At least one, and no default. A key's authority is the one thing about it
		// worth stating out loud, and a default here would be a permission granted by
		// omission.
		@NotEmpty(message = "must name at least one scope")
		List<String> scopes,

		// Null for a key that never expires (FR-8.7). Capped at a year because a
		// lifetime longer than that is indistinguishable from none, and asking for one
		// usually means the caller wanted none.
		@Min(value = 1, message = "must be at least 1 day")
		@Max(value = 365, message = "must be at most 365 days")
		Integer expiresInDays
) {

	/**
	 * Bean validation cannot express "every element is one of these" without a custom
	 * constraint, so the check lives here and reports through {@code detail} rather than
	 * the {@code errors} array. That is the right trade for this field: the browser
	 * sends checkboxes and cannot produce an unknown scope, so the only caller who ever
	 * sees this message is someone hand-writing a request, and what they need is the
	 * list of valid values rather than a field name they are already looking at.
	 */
	Set<Scope> toScopes() {
		Set<Scope> resolved = new LinkedHashSet<>();
		for (String value : scopes) {
			resolved.add(Scope.fromWireName(value).orElseThrow(() -> new ApiException(
					ProblemCode.VALIDATION_FAILED,
					"Unknown scope '" + value + "'. Valid scopes are "
							+ String.join(", ", Scope.all().stream().map(Scope::wireName).sorted().toList())
							+ ".")));
		}
		return resolved;
	}
}
