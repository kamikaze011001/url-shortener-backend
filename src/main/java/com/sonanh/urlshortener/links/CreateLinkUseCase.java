package com.sonanh.urlshortener.links;

import com.sonanh.urlshortener.links.internal.LinkRow;
import com.sonanh.urlshortener.links.internal.LinkWriter;
import com.sonanh.urlshortener.links.internal.ShortCodeGenerator;
import com.sonanh.urlshortener.shared.ApiException;
import com.sonanh.urlshortener.shared.AppProperties;
import com.sonanh.urlshortener.shared.ProblemCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-2: an Owner submits a Destination and receives a Short Link.
 *
 * <p>One class, one operation, one transaction boundary (ADR-0011).
 */
@Service
public class CreateLinkUseCase {

	private static final Logger log = LoggerFactory.getLogger(CreateLinkUseCase.class);

	/**
	 * Three attempts. At a 0.003% fill ratio three consecutive collisions is
	 * effectively impossible, so exhausting them is an alert, not a retry policy that
	 * needs tuning.
	 */
	private static final int MAX_ATTEMPTS = 3;

	private final ShortCodeGenerator generator;
	private final LinkWriter writer;
	private final AppProperties properties;
	private final Counter collisionRetries;

	CreateLinkUseCase(ShortCodeGenerator generator, LinkWriter writer,
			AppProperties properties, MeterRegistry meters) {
		this.generator = generator;
		this.writer = writer;
		this.properties = properties;
		this.collisionRetries = Counter.builder("urlshortener.code.collision.retries")
				.description("Short Code generation attempts that hit an existing code")
				.register(meters);
	}

	/**
	 * @param alias null to have a Short Code generated. When present it is claimed
	 *              exactly, or the request fails — it never silently falls back to a
	 *              generated code (FR-2.5).
	 */
	public record Command(UUID ownerId, String destination, String alias, Instant expiresAt) {}

	public record Result(
			long id,
			String code,
			String shortUrl,
			String destination,
			String status,
			boolean customAlias,
			long clickCount,
			Instant expiresAt,
			Instant createdAt,
			Instant updatedAt
	) {}

	@Transactional
	public Result execute(Command command) {
		LinkRow row = command.alias() != null
				? claimAlias(command)
				: generateCode(command);

		log.info("link.created code={} isCustomAlias={} ownerId={}",
				row.code(), row.customAlias(), command.ownerId());

		return toResult(row);
	}

	/** An Alias is claimed exactly once, or not at all. No retry — there is nothing to retry with. */
	private LinkRow claimAlias(Command command) {
		return writer.insert(command.alias(), command.destination(), command.ownerId(),
						true, command.expiresAt())
				.orElseThrow(() -> {
					log.info("link.create_rejected reason=ALIAS_TAKEN alias={}", command.alias());
					return ApiException.aliasTaken(command.alias());
				});
	}

	private LinkRow generateCode(Command command) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			Optional<LinkRow> inserted = writer.insert(generator.generate(),
					command.destination(), command.ownerId(), false, command.expiresAt());

			if (inserted.isPresent()) {
				return inserted.get();
			}
			collisionRetries.increment();
			log.warn("code.collision_retry attempt={}", attempt);
		}

		// Effectively unreachable. If this ever fires, the collision-retry metric has
		// been climbing for a while and the keyspace assumption in ADR-0002 is wrong.
		throw new ApiException(ProblemCode.INTERNAL,
				"Could not allocate a short code after " + MAX_ATTEMPTS + " attempts.");
	}

	private Result toResult(LinkRow row) {
		return new Result(
				row.id(),
				row.code(),
				properties.shortUrlFor(row.code()),
				row.destination(),
				row.status(),
				row.customAlias(),
				row.clickCount(),
				row.expiresAt(),
				row.createdAt(),
				row.updatedAt());
	}
}
