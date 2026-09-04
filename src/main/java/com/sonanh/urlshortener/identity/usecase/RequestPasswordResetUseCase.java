package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.domain.CodeMessages;
import com.sonanh.urlshortener.identity.domain.CodeVerdict;
import com.sonanh.urlshortener.identity.domain.OneTimeCode;
import com.sonanh.urlshortener.identity.store.OtpRepository;
import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.mail.MailSender;
import com.sonanh.urlshortener.shared.ratelimit.RateLimitPolicy;
import com.sonanh.urlshortener.shared.ratelimit.RedisRateLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-1.9: an Owner asks for a password reset code.
 *
 * <p><b>This use case cannot fail in a way the caller can observe.</b> Unknown address,
 * known address, mail server down — all return normally, and the controller answers
 * `202` in every case (FR-1.12).
 *
 * <p>That is not laziness about error handling, it is the requirement. Any observable
 * difference here is an account-enumeration oracle, and it would undo the uniform `401`
 * on login that exists for the same reason: an attacker who cannot learn which addresses
 * are registered from the login form would simply ask this endpoint instead.
 */
@Service
public class RequestPasswordResetUseCase {

	private static final Logger log = LoggerFactory.getLogger(RequestPasswordResetUseCase.class);

	/**
	 * FR-6.7's second bucket. It lives here rather than in {@code RateLimitFilter}
	 * because it keys on the target address, and a filter cannot read a request body
	 * without consuming it or wrapping the request to replay it — a real cost, for a
	 * limit the application layer can apply directly.
	 */
	private static final RateLimitPolicy PER_TARGET = RateLimitPolicy.perHour("reset-email", 3);

	private final OwnerRepository owners;
	private final OtpRepository codes;
	private final MailSender mail;
	private final RedisRateLimiter limiter;
	private final Clock clock;

	RequestPasswordResetUseCase(OwnerRepository owners, OtpRepository codes, MailSender mail,
			RedisRateLimiter limiter, Clock clock) {
		this.owners = owners;
		this.codes = codes;
		this.mail = mail;
		this.limiter = limiter;
		this.clock = clock;
	}

	public record Command(String email) {}

	@Transactional
	public void execute(Command command) {
		// Checked before the account is looked up, and deliberately: the bucket fills
		// whether or not the address is registered, so a 429 here reveals nothing about
		// who exists. Checking after the lookup would make the limit itself an oracle.
		var verdict = limiter.check(hashOf(command.email()), PER_TARGET);
		if (!verdict.allowed()) {
			throw new ApiException(ProblemCode.RATE_LIMITED,
					"Too many reset requests for that address. Try again in "
							+ verdict.retryAfterSeconds() + " seconds.");
		}

		var found = owners.findByEmail(command.email());

		if (found.isEmpty()) {
			// Logged so an operator can see the attempt; the response says nothing.
			log.info("auth.reset_requested outcome=NO_ACCOUNT");
			return;
		}

		OwnerRepository.OwnerRow owner = found.get();
		Instant now = clock.instant();
		String code = OneTimeCode.generate();

		codes.issue(owner.id(), OtpRepository.Purpose.PASSWORD_RESET,
				OneTimeCode.hash(code), now, now.plus(CodeVerdict.TTL));

		try {
			mail.send(CodeMessages.passwordReset(owner.email(), code));
			log.info("auth.reset_requested ownerId={} outcome=SENT", owner.id());
		}
		catch (MailSender.MailFailure ex) {
			// Swallowed on purpose. Surfacing it would make a mail outage look different
			// from an unknown address, which is the oracle this whole flow avoids. The
			// failure is not invisible — `urlshortener.mail.send{outcome=failed}` counts
			// it, and that is where an operator should be looking, not the user.
			log.warn("auth.reset_requested ownerId={} outcome=SEND_FAILED", owner.id());
		}
	}

	/** Hashed so the Redis keyspace never holds an email address (02-nfr.md § Privacy). */
	private String hashOf(String email) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}
}
