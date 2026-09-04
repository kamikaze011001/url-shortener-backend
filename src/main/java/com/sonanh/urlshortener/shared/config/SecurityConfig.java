package com.sonanh.urlshortener.shared.config;

import com.sonanh.urlshortener.shared.error.ProblemCode;
import com.sonanh.urlshortener.shared.error.ProblemWriter;
import com.sonanh.urlshortener.shared.http.ClientRequest;
import com.sonanh.urlshortener.shared.ratelimit.RateLimitFilter;
import com.sonanh.urlshortener.shared.ratelimit.RedisRateLimiter;
import com.sonanh.urlshortener.shared.security.JwtCodec;
import com.sonanh.urlshortener.shared.security.JwtCookieAuthFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
	public SecurityFilterChain filterChain(HttpSecurity http, JwtCodec jwtCodec,
			RedisRateLimiter rateLimiter, ClientRequest clientRequest, MeterRegistry meters)
			throws Exception {

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

				// After the auth filter, so the per-Owner limit can see a principal, and
				// before the endpoints, because the cheapest request to serve is one that
				// never reaches a use case. Constructed here rather than injected for the
				// CGLIB reason spelled out on the filter itself.
				.addFilterAfter(new RateLimitFilter(rateLimiter, clientRequest, meters),
						JwtCookieAuthFilter.class)

				// Without this, an unauthenticated request gets Spring's HTML login
				// redirect instead of the problem+json every other error uses.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, ex) ->
								ProblemWriter.write(response, ProblemCode.UNAUTHENTICATED, "Not authenticated."))
						.accessDeniedHandler((request, response, ex) ->
								ProblemWriter.write(response, ProblemCode.NOT_FOUND, "Not found.")))
				.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(10);
	}

}
