package com.sonanh.urlshortener.identity.web;

import com.sonanh.urlshortener.identity.store.OwnerRepository;
import com.sonanh.urlshortener.identity.usecase.LoginUseCase;
import com.sonanh.urlshortener.identity.usecase.RegisterOwnerUseCase;
import com.sonanh.urlshortener.shared.error.ApiException;
import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.security.CurrentOwner;
import com.sonanh.urlshortener.shared.security.JwtCodec;
import com.sonanh.urlshortener.shared.security.SessionCookies;
import jakarta.validation.Valid;
import java.time.Clock;
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
	private final OwnerRepository owners;
	private final JwtCodec jwt;
	private final SessionCookies cookies;
	private final CurrentOwner currentOwner;
	private final Clock clock;

	AuthController(RegisterOwnerUseCase register, LoginUseCase login, OwnerRepository owners,
			JwtCodec jwt, SessionCookies cookies, CurrentOwner currentOwner, Clock clock) {
		this.register = register;
		this.login = login;
		this.owners = owners;
		this.jwt = jwt;
		this.cookies = cookies;
		this.currentOwner = currentOwner;
		this.clock = clock;
	}

	@PostMapping("/register")
	ResponseEntity<OwnerResponse> register(@Valid @RequestBody RegisterRequest request) {
		var result = register.execute(new RegisterOwnerUseCase.Command(
				request.email(), request.password()));

		return ResponseEntity.status(HttpStatus.CREATED)
				.header(HttpHeaders.SET_COOKIE, sessionFor(result.ownerId()))
				.body(new OwnerResponse(result.ownerId().toString(), result.email(), result.createdAt()));
	}

	@PostMapping("/login")
	ResponseEntity<OwnerResponse> login(@Valid @RequestBody LoginRequest request) {
		var result = login.execute(new LoginUseCase.Command(request.email(), request.password()));

		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, sessionFor(result.ownerId()))
				.body(new OwnerResponse(result.ownerId().toString(), result.email(), result.createdAt()));
	}

	/**
	 * Clears the cookie in this browser. The token itself stays valid until it expires —
	 * there is no revocation list, and pretending otherwise would be worse than saying so.
	 */
	@PostMapping("/logout")
	ResponseEntity<Void> logout() {
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
				.build();
	}

	/**
	 * The only way the frontend can answer "am I logged in?", since the session cookie
	 * is httpOnly and unreadable from JavaScript.
	 */
	@GetMapping("/me")
	OwnerResponse me() {
		var owner = owners.findById(currentOwner.id())
				.orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "Not authenticated."));

		return new OwnerResponse(owner.id().toString(), owner.email(), owner.createdAt());
	}

	private String sessionFor(java.util.UUID ownerId) {
		return cookies.issue(jwt.issue(ownerId, clock.instant()), jwt.ttl()).toString();
	}
}
