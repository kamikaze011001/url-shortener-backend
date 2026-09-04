package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.domain.CodeMessages;
import com.sonanh.urlshortener.identity.domain.CodeVerdict;
import com.sonanh.urlshortener.identity.domain.OneTimeCode;
import com.sonanh.urlshortener.identity.store.OtpRepository;
import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.mail.MailSender;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-1.8: issue a verification code and email it.
 *
 * <p>Called both by registration and by an explicit resend, and the two want different
 * things from a failed send — so the caller decides, via {@code failQuietly}. That is a
 * flag on a use case, which is usually a smell; it is here because the two callers
 * differ in exactly one respect and splitting them would duplicate everything else.
 */
@Service
public class SendVerificationCodeUseCase {

	private static final Logger log = LoggerFactory.getLogger(SendVerificationCodeUseCase.class);

	private final OwnerRepository owners;
	private final OtpRepository codes;
	private final MailSender mail;
	private final Clock clock;

	SendVerificationCodeUseCase(OwnerRepository owners, OtpRepository codes, MailSender mail, Clock clock) {
		this.owners = owners;
		this.codes = codes;
		this.mail = mail;
		this.clock = clock;
	}

	/**
	 * @param failQuietly true during registration. An SMTP blip must not roll back an
	 *                    account that was otherwise created successfully — the Owner
	 *                    exists, can sign in, and can ask for another code. False on an
	 *                    explicit resend, where silence would be a lie: the user pressed
	 *                    a button whose entire purpose is to make an email arrive.
	 */
	public record Command(UUID ownerId, boolean failQuietly) {}

	@Transactional
	public void execute(Command command) {
		OwnerRepository.OwnerRow owner = owners.findById(command.ownerId())
				.orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "Not authenticated."));

		// Nothing to do, and nothing to say about it. Answering identically whether or
		// not a code went out keeps this endpoint from reporting account state that the
		// caller should be reading from /auth/me.
		if (owner.emailVerified()) {
			return;
		}

		Instant now = clock.instant();
		String code = OneTimeCode.generate();

		codes.issue(owner.id(), OtpRepository.Purpose.EMAIL_VERIFICATION,
				OneTimeCode.hash(code), now, now.plus(CodeVerdict.TTL));

		try {
			mail.send(CodeMessages.verification(owner.email(), code));
			log.info("auth.verification_sent ownerId={}", owner.id());
		}
		catch (MailSender.MailFailure ex) {
			if (!command.failQuietly()) {
				// Rolls back the code that was just issued, so the Owner is not left
				// with a live code they never received while a later resend retires it.
				throw new ApiException(ProblemCode.INTERNAL,
						"Could not send the email just now. Try again in a moment.");
			}
			log.warn("auth.verification_send_failed ownerId={} (registration continued)", owner.id());
		}
	}
}
