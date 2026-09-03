package com.sonanh.urlshortener.shared.security;

import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Reads the authenticated Owner, so controllers do not each reach into the security
 * context and interpret it their own way.
 */
@Component
public class CurrentOwner {

	/**
	 * @throws ApiException UNAUTHENTICATED when there is no authenticated Owner. On a
	 *                      chain that already requires authentication this is
	 *                      unreachable, and throwing rather than returning null means a
	 *                      future misconfiguration surfaces as a 401 instead of a
	 *                      NullPointerException halfway through a use case.
	 */
	public UUID id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof UUID ownerId)) {
			throw new ApiException(ProblemCode.UNAUTHENTICATED, "Not authenticated.");
		}
		return ownerId;
	}
}
