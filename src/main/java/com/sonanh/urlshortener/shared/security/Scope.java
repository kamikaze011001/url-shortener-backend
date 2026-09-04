package com.sonanh.urlshortener.shared.security;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * What a credential is allowed to do (FR-8.9, ADR-0020).
 *
 * <p>Lives in {@code shared} rather than {@code identity} because the thing that
 * <i>enforces</i> a scope is the web edge of every module, while the thing that
 * <i>grants</i> one is an API Key in {@code identity}. Putting it beside the key would
 * make {@code links} depend on {@code identity} to ask a question about its own
 * endpoints.
 *
 * <p>There is deliberately no scope for managing API Keys. FR-8.5 is a containment
 * property, not a permission — a permission is something that can be granted, and
 * nothing should be able to grant that one. It is enforced by
 * {@link CurrentOwner#requireSessionCredential()} instead.
 */
public enum Scope {

	/** Read Links, their statistics and their Destination history. */
	LINKS_READ("links:read"),

	/**
	 * Create, edit, disable and delete Links.
	 *
	 * <p>Does <b>not</b> imply {@link #LINKS_READ}. Implication would make write-only
	 * inexpressible, and write-only is the shape a load generator wants: a credential
	 * that can create Links but cannot enumerate the Owner's existing ones.
	 */
	LINKS_WRITE("links:write");

	private final String wireName;

	Scope(String wireName) {
		this.wireName = wireName;
	}

	/** The string in the API and in the {@code scopes} column. Part of the contract. */
	public String wireName() {
		return wireName;
	}

	/**
	 * @return empty for anything not in the vocabulary. Callers decide what an unknown
	 *         scope means — a request naming one is a validation failure, while a row
	 *         holding one would be a database that got past its own CHECK constraint.
	 */
	public static Optional<Scope> fromWireName(String value) {
		return Arrays.stream(values()).filter(scope -> scope.wireName.equals(value)).findFirst();
	}

	/**
	 * Every scope. What a session cookie carries — a session is the human, acting on
	 * their own account, and scoping that would be scoping a person's access to their
	 * own dashboard.
	 */
	public static Set<Scope> all() {
		return Set.of(values());
	}
}
