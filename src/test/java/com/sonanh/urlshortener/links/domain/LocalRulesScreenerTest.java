package com.sonanh.urlshortener.links.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sonanh.urlshortener.links.domain.DestinationScreener.Refusal;
import com.sonanh.urlshortener.shared.config.AppProperties;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The guard a reviewer will actually try to break, and the one place where a bug is
 * invisible in a demo — a broken SSRF check looks exactly like a working one until
 * someone points it at something interesting.
 *
 * <p>No Spring context and no database: this class is pure logic, which is the return
 * on keeping it in {@code domain/}.
 */
class LocalRulesScreenerTest {

	private final LocalRulesScreener screener = new LocalRulesScreener(properties());

	private static AppProperties properties() {
		return new AppProperties(
				"http://localhost:8080",
				UUID.randomUUID(),
				"test-salt",
				List.of("s.example.com"),
				new AppProperties.Security("secret", Duration.ofHours(1), false));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://10.0.0.1/",
			"http://172.16.0.1/",
			"http://192.168.1.1/admin",
			"http://127.0.0.1:8080/",
			"http://169.254.169.254/latest/meta-data/",   // cloud metadata
			"http://0.0.0.0/",
			"http://100.64.0.1/",                          // carrier-grade NAT
			"http://[::1]/",
			"http://[fd00::1]/",                           // IPv6 unique local
	})
	@DisplayName("refuses addresses the public internet cannot reach")
	void refusesPrivateAddresses(String url) {
		var verdict = screener.screen(URI.create(url));

		assertThat(verdict.allowed()).isFalse();
		assertThat(verdict.reason()).isEqualTo(Refusal.PRIVATE_ADDRESS);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"ftp://example.com/file",
			"javascript:alert(1)",
			"file:///etc/passwd",
			"/relative/path",
			// A data: URL that parses. The <script> variant every SSRF list quotes does
			// not even survive URI parsing, so it is refused a step earlier as
			// INVALID_DESTINATION and never reaches the screener.
			"data:text/plain,hello",
	})
	@DisplayName("refuses anything that is not an absolute http(s) URL")
	void refusesNonHttpSchemes(String url) {
		assertThat(screener.screen(URI.create(url)).reason()).isEqualTo(Refusal.NOT_HTTP);
	}

	@Test
	@DisplayName("refuses our own short host, which resolves publicly and would loop")
	void refusesSelfReference() {
		assertThat(screener.screen(URI.create("https://s.example.com/abc")).reason())
				.isEqualTo(Refusal.SELF_REFERENCE);
	}

	@Test
	@DisplayName("refuses a host that does not resolve, rather than accepting it")
	void failsClosedOnUnresolvableHost() {
		var verdict = screener.screen(URI.create("http://this-host-does-not-exist.invalid/"));

		assertThat(verdict.allowed()).isFalse();
		assertThat(verdict.reason()).isEqualTo(Refusal.UNRESOLVABLE_HOST);
	}

	/**
	 * The test that matters most. A screener that checks the hostname string passes
	 * every other test in this class and still lets an attacker reach the private
	 * network: {@code localtest.me} is a public domain whose A record is 127.0.0.1.
	 */
	@Test
	@DisplayName("refuses a public hostname that RESOLVES to a private address")
	void refusesPublicHostnameResolvingToPrivateAddress() throws Exception {
		InetAddress resolved = InetAddress.getByName("localtest.me");
		assertThat(resolved.isLoopbackAddress())
				.as("precondition: localtest.me still resolves to loopback")
				.isTrue();

		var verdict = screener.screen(URI.create("http://localtest.me/admin"));

		assertThat(verdict.allowed()).isFalse();
		assertThat(verdict.reason()).isEqualTo(Refusal.PRIVATE_ADDRESS);
	}

	@Test
	@DisplayName("allows an ordinary public URL")
	void allowsPublicUrl() {
		assertThat(screener.screen(URI.create("https://www.anthropic.com/news")).allowed()).isTrue();
	}
}
