package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-1.2: an Owner logs in and receives a session. */
@Service
public class LoginUseCase {

	private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

	/**
	 * A real bcrypt hash of nothing in particular. Verifying the supplied password
	 * against it when the email is unknown keeps the response time of "no such account"
	 * indistinguishable from "wrong password" — without it, the endpoint answers
	 * unknown emails in a millisecond and known ones in eighty, which is an account
	 * enumeration oracle wearing a different hat.
	 */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final OwnerRepository owners;
	private final PasswordEncoder passwordEncoder;

	LoginUseCase(OwnerRepository owners, PasswordEncoder passwordEncoder) {
		this.owners = owners;
		this.passwordEncoder = passwordEncoder;
	}

	public record Command(String email, String password) {}

	public record Result(UUID ownerId, String email, Instant createdAt) {}

	/**
	 * Answers UNAUTHENTICATED for both a wrong password and an unknown email. The two
	 * are deliberately indistinguishable — the same reasoning that makes every
	 * non-Active Link an identical 404 (ADR-0008).
	 */
	@Transactional(readOnly = true)
	public Result execute(Command command) {
		Optional<OwnerRepository.OwnerRow> found = owners.findByEmail(command.email());

		String hash = found.map(OwnerRepository.OwnerRow::passwordHash).orElse(DUMMY_HASH);
		boolean matches = passwordEncoder.matches(command.password(), hash);

		if (found.isEmpty() || !matches) {
			log.info("auth.login_failed email={}", command.email());
			throw new ApiException(ProblemCode.UNAUTHENTICATED, "Incorrect email or password.");
		}

		OwnerRepository.OwnerRow owner = found.get();
		log.info("auth.login_succeeded ownerId={}", owner.id());
		return new Result(owner.id(), owner.email(), owner.createdAt());
	}
}
