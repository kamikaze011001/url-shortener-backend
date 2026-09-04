package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.identity.usecase.LoginUseCase;
import com.sonanh.urlshortener.identity.usecase.RegisterOwnerUseCase;
import com.sonanh.urlshortener.identity.usecase.RequestPasswordResetUseCase;
import com.sonanh.urlshortener.identity.usecase.ResetPasswordUseCase;
import com.sonanh.urlshortener.identity.usecase.SendVerificationCodeUseCase;
import com.sonanh.urlshortener.identity.usecase.VerifyEmailUseCase;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.security.CurrentOwner;
import com.sonanh.urlshortener.shared.security.JwtCodec;
import com.sonanh.urlshortener.shared.security.SessionCookies;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final RegisterOwnerUseCase register;
	private final LoginUseCase login;
	private final VerifyEmailUseCase verifyEmail;
	private final SendVerificationCodeUseCase sendVerification;
	private final RequestPasswordResetUseCase requestReset;
	private final ResetPasswordUseCase resetPassword;
	private final OwnerRepository owners;
	private final JwtCodec jwt;
	private final SessionCookies cookies;
	private final CurrentOwner currentOwner;
	private final Clock clock;

	AuthController(RegisterOwnerUseCase register, LoginUseCase login, VerifyEmailUseCase verifyEmail,
			SendVerificationCodeUseCase sendVerification, RequestPasswordResetUseCase requestReset,
			ResetPasswordUseCase resetPassword, OwnerRepository owners, JwtCodec jwt,
			SessionCookies cookies, CurrentOwner currentOwner, Clock clock) {
		this.register = register;
		this.login = login;
		this.verifyEmail = verifyEmail;
		this.sendVerification = sendVerification;
		this.requestReset = requestReset;
		this.resetPassword = resetPassword;
		this.owners = owners;
		this.jwt = jwt;
		this.cookies = cookies;
		this.currentOwner = currentOwner;
		this.clock = clock;
	}

	/**
	 * Registration signs the Owner in immediately, unverified. They can look around and
	 * recover; they cannot create a Link until they confirm the address (FR-1.7).
	 */
	@PostMapping("/register")
	ResponseEntity<OwnerResponse> register(@Valid @RequestBody RegisterRequest request) {
		var result = register.execute(new RegisterOwnerUseCase.Command(
				request.email(), request.password()));

		// Separate transaction from the registration itself, and quiet about failure: an
		// SMTP blip must not undo an account that was otherwise created. The Owner can
		// ask for another code, which is the path that does report failure.
		sendVerification.execute(new SendVerificationCodeUseCase.Command(result.ownerId(), true));

		return ResponseEntity.status(HttpStatus.CREATED)
				.header(HttpHeaders.SET_COOKIE, sessionFor(result.ownerId(), result.tokenVersion()))
				.body(new OwnerResponse(result.ownerId().toString(), result.email(),
						result.emailVerified(), result.createdAt()));
	}

	@PostMapping("/login")
	ResponseEntity<OwnerResponse> login(@Valid @RequestBody LoginRequest request) {
		var result = login.execute(new LoginUseCase.Command(request.email(), request.password()));

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, sessionFor(result.ownerId(), result.tokenVersion()))
				.body(new OwnerResponse(result.ownerId().toString(), result.email(),
						result.emailVerified(), result.createdAt()));
	}

	/**
	 * Clears the cookie in this browser. The token itself stays valid until it expires —
	 * ending every session is what a password reset does, and doing it on an ordinary
	 * logout would sign the Owner out of their other devices without being asked.
	 */
	@PostMapping("/logout")
	ResponseEntity<Void> logout() {
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
				.build();
	}

	/**
	 * The only way the frontend can answer "am I logged in, and am I verified?", since
	 * the session cookie is httpOnly and unreadable from JavaScript.
	 */
	@GetMapping("/me")
	OwnerResponse me() {
		var owner = owners.findById(currentOwner.id())
				.orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "Not authenticated."));

		return new OwnerResponse(owner.id().toString(), owner.email(), owner.emailVerified(),
				owner.createdAt());
	}

	@PostMapping("/verify-email")
	OwnerResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		var result = verifyEmail.execute(new VerifyEmailUseCase.Command(
				currentOwner.id(), request.code()));

		// True by construction: the use case either verified them or threw.
		return new OwnerResponse(result.ownerId().toString(), result.email(), true, result.createdAt());
	}

	/** Loud about failure, unlike registration — the button exists to make mail arrive. */
	@PostMapping("/resend-verification")
	ResponseEntity<Void> resendVerification() {
		sendVerification.execute(new SendVerificationCodeUseCase.Command(currentOwner.id(), false));
		return ResponseEntity.accepted().build();
	}

	/**
	 * Always `202`, whether or not the address is registered (FR-1.12). Any observable
	 * difference here is an account-enumeration oracle, and it would undo the uniform
	 * `401` on login that exists for the same reason.
	 */
	@PostMapping("/forgot-password")
	ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
		requestReset.execute(new RequestPasswordResetUseCase.Command(request.email()));
		return ResponseEntity.accepted().build();
	}

	/**
	 * No cookie is issued. Every session for this Owner has just been invalidated,
	 * including whichever one made this call, so the Owner signs in again with the new
	 * password — which is also the proof that it took effect.
	 */
	@PostMapping("/reset-password")
	ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
		resetPassword.execute(new ResetPasswordUseCase.Command(
				request.email(), request.code(), request.password()));

		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
				.build();
	}

	private String sessionFor(UUID ownerId, int tokenVersion) {
		return cookies.issue(jwt.issue(ownerId, tokenVersion, clock.instant()), jwt.ttl()).toString();
	}
}
