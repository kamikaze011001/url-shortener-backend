package com.sonanh.urlshortener.identity.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The {@code otp_codes} table. */
@Component
public class OtpRepository {

	/**
	 * Issuing a code retires any earlier live one for the same purpose. Without this an
	 * Owner who pressed resend three times would have three working codes, and the
	 * five-attempt limit would be five attempts *per code* rather than per person.
	 */
	private static final String CONSUME_OUTSTANDING = """
			UPDATE otp_codes SET consumed_at = ?
			WHERE owner_id = ? AND purpose = ? AND consumed_at IS NULL
			""";

	private static final String INSERT = """
			INSERT INTO otp_codes (owner_id, purpose, code_hash, expires_at)
			VALUES (?, ?, ?, ?)
			""";

	private static final String SELECT_LIVE = """
			SELECT id, code_hash, attempts, expires_at
			FROM otp_codes
			WHERE owner_id = ? AND purpose = ? AND consumed_at IS NULL
			ORDER BY created_at DESC
			LIMIT 1
			""";

	private static final String RECORD_ATTEMPT = "UPDATE otp_codes SET attempts = attempts + 1 WHERE id = ?";

	private static final String CONSUME = "UPDATE otp_codes SET consumed_at = ? WHERE id = ?";

	private static final RowMapper<OtpRow> MAPPER = (rs, n) -> new OtpRow(
			rs.getLong("id"),
			rs.getString("code_hash"),
			rs.getShort("attempts"),
			rs.getTimestamp("expires_at").toInstant());

	private final JdbcTemplate jdbc;
	private final TransactionTemplate attemptTx;

	OtpRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;

		// REQUIRES_NEW, for the same reason SyncClickRecorder uses it: this write has to
		// survive its caller's outcome. Every caller records an attempt and then throws
		// to report the wrong code — and a throw rolls back the surrounding transaction,
		// taking the increment with it.
		//
		// That is not hypothetical. It shipped, and five wrong guesses left `attempts`
		// at zero, so the five-attempt limit did nothing at all and the code stayed
		// live. A limit that quietly does not apply is worse than no limit, because the
		// requirement is ticked off.
		this.attemptTx = new TransactionTemplate(txManager);
		this.attemptTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void issue(UUID ownerId, Purpose purpose, String codeHash, Instant now, Instant expiresAt) {
		jdbc.update(CONSUME_OUTSTANDING, Timestamp.from(now), ownerId, purpose.name());
		jdbc.update(INSERT, ownerId, purpose.name(), codeHash, Timestamp.from(expiresAt));
	}

	public Optional<OtpRow> findLive(UUID ownerId, Purpose purpose) {
		List<OtpRow> rows = jdbc.query(SELECT_LIVE, MAPPER, ownerId, purpose.name());
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	/**
	 * Commits on its own, immediately, whatever the caller does next.
	 *
	 * <p>See the constructor: the callers all count an attempt and then throw, so this
	 * must not share their transaction.
	 */
	public void recordAttempt(long id) {
		attemptTx.executeWithoutResult(status -> jdbc.update(RECORD_ATTEMPT, id));
	}

	public void consume(long id, Instant now) {
		jdbc.update(CONSUME, Timestamp.from(now), id);
	}

	public enum Purpose {
		EMAIL_VERIFICATION, PASSWORD_RESET
	}

	public record OtpRow(long id, String codeHash, short attempts, Instant expiresAt) {}
}
