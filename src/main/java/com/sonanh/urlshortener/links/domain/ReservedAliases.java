package com.sonanh.urlshortener.links.domain;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strings permanently withheld from the Code Namespace.
 *
 * <p>Enforced here rather than by a database constraint because the list will change,
 * and a migration per word is absurd.
 *
 * <p>The comparison is <b>case-insensitive, and only here</b>: {@code Admin} is as
 * reserved as {@code admin}, even though the two are otherwise distinct Short Codes.
 * Blocking a reserved word is about preventing confusion, not about namespace
 * mechanics.
 *
 * <p>The authoritative list is in the KB's 04-data-model.md. This mirrors it.
 */
@Component
public class ReservedAliases {

	private static final Set<String> RESERVED = Set.of(
			"api", "app", "admin", "auth", "login", "logout", "register", "signup", "signin",
			"dashboard", "settings", "account", "health", "status", "metrics", "actuator",
			"static", "assets", "public", "favicon.ico", "robots.txt", "sitemap.xml",
			"_next", ".well-known", "s", "www", "help", "about", "terms", "privacy", "support");

	public boolean isReserved(String alias) {
		return alias != null && RESERVED.contains(alias.toLowerCase(Locale.ROOT));
	}
}
