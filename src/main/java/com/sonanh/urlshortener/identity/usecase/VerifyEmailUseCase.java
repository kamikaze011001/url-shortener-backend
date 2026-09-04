package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.domain.CodeVerdict;
import com.sonanh.urlshortener.identity.store.OtpRepository;
import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.security.OwnerAuthState;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-1.6: an Owner confirms their email address with a code. */
@Service
public class VerifyEmailUseCase {

	private static final Logger log = LoggerFactory.getLogger(VerifyEmailUseCase.class);

	private final OwnerRepository owners;
	private final OtpRepository codes;
	private final OwnerAuthState authState;
	private final Clock clock;

	VerifyEmailUseCase(OwnerRepository owners, OtpRepository codes, OwnerAuthState authState, Clock clock) {
		this.owners = owners;
		this.codes = codes;
		this.authState = authState;
		this.clock = clock;
	}

	public record Command(UUID ownerId, String code) {}

	public record Result(UUID ownerId, String email, Instant createdAt) {}


	@Transactional
	public Result execute(Command command) {
		Instant now = clock.instant();

		OwnerRepository.OwnerRow owner = owners.findById(command.ownerId())
				.orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "Not authenticated."));

		// Already verified is success, not an error. A user who submits a code twice —
		// double-clicking, or a retried request — should see the state they wanted, and
		// telling them off for arriving at the right destination helps nobody.
		if (owner.emailVerified()) {
			return new Result(owner.id(), owner.email(), owner.createdAt());
		}

		var stored = codes.findLive(command.ownerId(), OtpRepository.Purpose.EMAIL_VERIFICATION)
				.orElseThrow(() -> new ApiException(ProblemCode.INVALID_CODE,
						"That code is not valid. Request a new one."));

		CodeVerdict.Result verdict = CodeVerdict.check(
				stored.codeHash(), stored.attempts(), stored.expiresAt(), command.code(), now);

		switch (verdict) {
			case EXPIRED -> throw new ApiException(ProblemCode.CODE_EXPIRED,
					"That code has expired. Request a new one.");
			case EXHAUSTED -> throw new ApiException(ProblemCode.INVALID_CODE,
					"That code is no longer usable. Request a new one.");
			case WRONG -> {
				// Commits in its own transaction — see OtpRepository. Counting it here
				// and then throwing would roll the increment back, which is exactly the
				// bug that shipped and made the five-attempt limit a no-op.
				codes.recordAttempt(stored.id());
				log.info("auth.verify_failed ownerId={} reason=WRONG", command.ownerId());
				throw new ApiException(ProblemCode.INVALID_CODE, "That code is not correct.");
			}
			case OK -> {
				codes.consume(stored.id(), now);
				owners.markVerified(owner.id());
				// The cached auth state says unverified until this is dropped, and the
				// next request would refuse to create a Link with a straight face.
				authState.invalidate(owner.id());
				log.info("auth.email_verified ownerId={}", owner.id());
			}
		}

		return new Result(owner.id(), owner.email(), owner.createdAt());
	}
}
