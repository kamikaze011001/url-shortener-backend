package com.sonanh.urlshortener.links;

import java.util.Optional;

/**
 * The only way another module may resolve a Short Code.
 *
 * <p>Exists so that {@code redirect} has a visible, declared dependency on
 * {@code links} rather than an invisible one via the {@code links} table. Modulith
 * verifies code dependencies, not data dependencies — two modules querying the same
 * table would pass the boundary test while carrying a real coupling.
 *
 * <p>The return value carries exactly what a Redirect needs and nothing more. It
 * deliberately excludes the click count, which would be stale the instant anyone
 * clicked (ADR-0004).
 */
public interface LinkLookup {

	Optional<LinkTarget> findByCode(String code);

	/**
	 * @param status     ACTIVE or DISABLED as stored; expiry and deletion are derived
	 *                   by the caller, so all four non-redirectable cases can be
	 *                   collapsed into one identical 404 (ADR-0008).
	 * @param expiresAt  null means no expiry.
	 * @param deleted    soft-delete marker; a deleted Link never releases its code.
	 */
	record LinkTarget(
			long linkId,
			String code,
			String destination,
			String status,
			java.time.Instant expiresAt,
			boolean deleted
	) {
		public boolean isRedirectable(java.time.Instant now) {
			return !deleted
					&& "ACTIVE".equals(status)
					&& (expiresAt == null || expiresAt.isAfter(now));
		}
	}
}
