package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.domain.CodeVerdict;
import com.sonanh.urlshortener.identity.store.OtpRepository;
import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.security.OwnerAuthState;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-1.9 and FR-1.10: a new password is set with a code, and every session ends. */
@Service
public class ResetPasswordUseCase {

	private static final Logger log = LoggerFactory.getLogger(ResetPasswordUseCase.class);

	/** Matches LoginUseCase, and for the same reason. */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final OwnerRepository owners;
	private final OtpRepository codes;
	private final OwnerAuthState authState;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	ResetPasswordUseCase(OwnerRepository owners, OtpRepository codes, OwnerAuthState authState,
			PasswordEncoder passwordEncoder, Clock clock) {
		this.owners = owners;
		this.codes = codes;
		this.authState = authState;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
	}

	public record Command(String email, String code, String password) {}

	/**
	 * An unknown address answers INVALID_CODE, exactly as a known address with the wrong
	 * digits does. The endpoint is unauthenticated and takes an email, so any other
	 * answer would tell a stranger whether that address is registered — the same oracle
	 * {@link RequestPasswordResetUseCase} refuses to open.
	 *
	 * <p>The bcrypt encode runs either way, so the two also take about the same time.
	 */
	@Transactional
	public void execute(Command command) {
		Instant now = clock.instant();
		var found = owners.findByEmail(command.email());

		if (found.isEmpty()) {
			passwordEncoder.matches(command.password(), DUMMY_HASH);
			log.info("auth.reset_failed reason=NO_ACCOUNT");
			throw new ApiException(ProblemCode.INVALID_CODE, "That code is not correct.");
		}

		OwnerRepository.OwnerRow owner = found.get();

		var stored = codes.findLive(owner.id(), OtpRepository.Purpose.PASSWORD_RESET)
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
				codes.recordAttempt(stored.id());
				log.info("auth.reset_failed ownerId={} reason=WRONG", owner.id());
				throw new ApiException(ProblemCode.INVALID_CODE, "That code is not correct.");
			}
			case OK -> {
				codes.consume(stored.id(), now);

				// One statement sets the password and bumps the version, so there is no
				// instant where the new password works and the old sessions still do.
				owners.resetPassword(owner.id(), passwordEncoder.encode(command.password()));

				// Without this the cached version is the old one, and every session the
				// reset was meant to kill keeps working until the cache expires.
				authState.invalidate(owner.id());

				log.info("auth.password_reset ownerId={} sessions_invalidated=true", owner.id());
			}
		}
	}
}
