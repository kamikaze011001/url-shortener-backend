package com.sonanh.urlshortener.identity.internal;

import com.sonanh.urlshortener.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TEMPORARY, and local-profile only.
 *
 * <p>{@code links.owner_id} is NOT NULL, but authentication does not exist yet. Rather
 * than relax the schema — it is the source of truth — or seed through a migration,
 * which would ship to production, the local profile inserts one fixed Owner and
 * requests are attributed to it until step 4.
 *
 * <p>When authentication lands this either disappears or becomes the demo seed.
 */
@Configuration
@Profile("local")
class DevOwnerInitializer {

	private static final Logger log = LoggerFactory.getLogger(DevOwnerInitializer.class);

	private static final String UPSERT = """
			INSERT INTO owners (id, email, password_hash)
			VALUES (?, 'dev@localhost', '!not-a-real-hash')
			ON CONFLICT (id) DO NOTHING
			""";

	@Bean
	ApplicationRunner seedDevOwner(JdbcTemplate jdbc, AppProperties properties) {
		return args -> {
			jdbc.update(UPSERT, properties.devOwnerId());
			log.info("dev.owner_seeded id={} (local profile only)", properties.devOwnerId());
		};
	}
}
