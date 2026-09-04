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
		return authenticated().id();
	}

	/**
	 * Refuses the request when the Owner has not confirmed their email address (FR-1.7).
	 *
	 * <p>Called at the web edge rather than inside a use case, and deliberately: this is
	 * a fact about the <i>caller</i>, in the same category as "is this request
	 * authenticated at all", which the security chain already answers there. A use case
	 * that reached into the security context to find out would be an edge concern
	 * wearing business clothing.
	 *
	 * <p>Answers {@code 403}, not the uniform {@code 404} of ADR-0008. That rule exists
	 * so a caller cannot learn whether a <i>resource</i> exists; here they are asking
	 * about their own account, already know it exists, and need to be told what to do.
	 */
	public void requireVerified() {
		if (!authenticated().emailVerified()) {
			throw new ApiException(ProblemCode.EMAIL_NOT_VERIFIED,
					"Confirm your email address before creating links.");
		}
	}

	public boolean isVerified() {
		return authenticated().emailVerified();
	}

	private AuthenticatedOwner authenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null
				|| !(authentication.getPrincipal() instanceof AuthenticatedOwner owner)) {
			throw new ApiException(ProblemCode.UNAUTHENTICATED, "Not authenticated.");
		}
		return owner;
	}
}
