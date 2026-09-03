package com.sonanh.urlshortener.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SharedConfig {

	/**
	 * Injected rather than called statically, so expiry behaviour is testable without
	 * waiting for wall-clock time to pass.
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
