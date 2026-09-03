package com.sonanh.urlshortener.shared.config;

import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.security.JwtCodec;
import com.sonanh.urlshortener.shared.security.JwtCookieAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Note for Spring Security 7 (Spring Boot 4): the lambda DSL is the only DSL, and the
 * {@code and()} chaining in most examples online — which are written for 3.x — no
 * longer exists.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, JwtCodec jwtCodec) throws Exception {

		return http
				// The session cookie is SameSite=Strict, which blocks the cross-site form
				// post a CSRF token would defend against. There is no other browser-form
				// surface, and the API is not cookie-authenticated from anywhere else.
				.csrf(csrf -> csrf.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				.authorizeHttpRequests(auth -> auth
						// The redirect path. Public by definition — a Visitor is never
						// authenticated, and requiring auth here would break every link.
						.requestMatchers(HttpMethod.GET, "/{code:[A-Za-z0-9_-]{3,32}}").permitAll()
						.requestMatchers(HttpMethod.GET, "/").permitAll()

						.requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
								"/api/v1/auth/logout").permitAll()
						.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()

						// Everything else, including /api/v1/links/** and the remaining
						// actuator endpoints. Default-deny: a new endpoint is protected
						// until someone deliberately opens it.
						.anyRequest().authenticated())

				.addFilterBefore(new JwtCookieAuthFilter(jwtCodec), UsernamePasswordAuthenticationFilter.class)

				// Without this, an unauthenticated request gets Spring's HTML login
				// redirect instead of the problem+json every other error uses.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, ex) ->
								writeProblem(response, ProblemCode.UNAUTHENTICATED, "Not authenticated."))
						.accessDeniedHandler((request, response, ex) ->
								writeProblem(response, ProblemCode.NOT_FOUND, "Not found.")))
				.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(10);
	}

	private void writeProblem(HttpServletResponse response, ProblemCode code, String detail)
			throws java.io.IOException {

		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("""
				{"type":"%s","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
				.formatted(code.type(), code.title(), code.status().value(), detail, code.name()));
	}
}
