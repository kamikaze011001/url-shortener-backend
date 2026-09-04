package com.sonanh.urlshortener.identity.usecase;

import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-1.1: an Owner registers with email and password. */
@Service
public class RegisterOwnerUseCase {

	private static final Logger log = LoggerFactory.getLogger(RegisterOwnerUseCase.class);

	private final OwnerRepository owners;
	private final PasswordEncoder passwordEncoder;

	RegisterOwnerUseCase(OwnerRepository owners, PasswordEncoder passwordEncoder) {
		this.owners = owners;
		this.passwordEncoder = passwordEncoder;
	}

	public record Command(String email, String password) {}

	public record Result(UUID ownerId, String email, boolean emailVerified, int tokenVersion,
			Instant createdAt) {}

	@Transactional
	public Result execute(Command command) {
		// bcrypt, cost 10. Hashing before the insert means a rejected registration costs
		// the same time as an accepted one, so response timing does not reveal whether
		// an email is already registered.
		String hash = passwordEncoder.encode(command.password());

		OwnerRepository.OwnerRow owner = owners.insert(command.email(), hash)
				.orElseThrow(() -> {
					log.info("auth.register_rejected reason=EMAIL_TAKEN");
					return new ApiException(ProblemCode.EMAIL_TAKEN, "That email is already registered.");
				});

		log.info("auth.registered ownerId={}", owner.id());
		return new Result(owner.id(), owner.email(), owner.emailVerified(), owner.tokenVersion(),
				owner.createdAt());
	}
}
