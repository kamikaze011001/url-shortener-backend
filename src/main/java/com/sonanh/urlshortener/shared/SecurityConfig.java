package com.sonanh.urlshortener.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * STEP 2: everything is public.
 *
 * <p>Spring Security is on the classpath, so without this every endpoint would answer
 * 401 with a generated password. This chain opens it up until authentication is built,
 * at which point {@code /api/v1/links/**} becomes authenticated and {@code /{code}}
 * and {@code /api/v1/auth/**} stay public.
 *
 * <p>Note for Spring Security 7 (Spring Boot 4): the lambda DSL is the only DSL, and
 * the {@code and()} chaining from older examples no longer exists.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				// No cookies are read yet, and the API is not browser-form driven.
				// When sessions arrive they are SameSite=Strict, which is what stands
				// in for CSRF tokens here (02-nfr.md § Security).
				.csrf(csrf -> csrf.disable())
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.build();
	}

	/** bcrypt, cost 10. Used from step 4 onward; declared here so there is one place. */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(10);
	}
}
