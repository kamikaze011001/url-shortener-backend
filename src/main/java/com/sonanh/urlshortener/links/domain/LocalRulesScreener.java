package com.sonanh.urlshortener.links.domain;

import com.sonanh.urlshortener.shared.config.AppProperties;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Screening that needs no external service, and therefore can never be unavailable.
 *
 * <p>The rule that earns this class: <b>the private-address check runs on the resolved
 * addresses, not on the hostname string.</b> Checking the string is how this guard is
 * usually written and always wrong — {@code evil.com} with an A record pointing at
 * {@code 10.0.0.1} passes a string check and turns this service into a proxy into
 * whatever network it runs in. For a process sitting on a home machine behind a tunnel,
 * that is a live concern rather than a theoretical one.
 *
 * <p>A known gap, recorded in ADR-0010 and not closed: a hostname can resolve to a
 * public address here and a private one when the Redirect is served. Closing that means
 * re-resolving on every Redirect, which is unaffordable on a 20 ms path.
 */
@Component
public class LocalRulesScreener implements DestinationScreener {

	private static final Logger log = LoggerFactory.getLogger(LocalRulesScreener.class);

	private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

	/**
	 * DNS has no timeout of its own and a slow resolver would otherwise hang a request
	 * thread indefinitely.
	 */
	private static final long RESOLVE_TIMEOUT_SECONDS = 2;

	private final Set<String> blockedHosts;
	private final ExecutorService resolver = Executors.newVirtualThreadPerTaskExecutor();

	LocalRulesScreener(AppProperties properties) {
		this.blockedHosts = properties.blockedHosts();
	}

	@Override
	public Verdict screen(URI destination) {
		String scheme = destination.getScheme();
		if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
			return Verdict.refuse(Refusal.NOT_HTTP);
		}

		String host = destination.getHost();
		if (host == null || host.isBlank()) {
			return Verdict.refuse(Refusal.NOT_HTTP);
		}

		if (blockedHosts.contains(host.toLowerCase(Locale.ROOT))) {
			// Our own hostname. Resolves to a public address behind Cloudflare, so the
			// private-address rule below would never catch it.
			return Verdict.refuse(Refusal.SELF_REFERENCE);
		}

		InetAddress[] addresses;
		try {
			addresses = resolve(host);
		}
		catch (Exception ex) {
			// Fail closed. An unresolvable host is a typo or a probe, and creation is
			// not latency-critical — accepting links to nowhere is worse than
			// occasionally refusing a domain whose DNS is having a bad day.
			log.info("link.create_rejected reason=UNRESOLVABLE_HOST host={} cause={}",
					host, ex.getClass().getSimpleName());
			return Verdict.refuse(Refusal.UNRESOLVABLE_HOST);
		}

		for (InetAddress address : addresses) {
			if (isPrivate(address)) {
				log.info("link.create_rejected reason=PRIVATE_ADDRESS host={}", host);
				return Verdict.refuse(Refusal.PRIVATE_ADDRESS);
			}
		}
		return Verdict.allow();
	}

	private InetAddress[] resolve(String host) throws Exception {
		Future<InetAddress[]> lookup = resolver.submit(() -> InetAddress.getAllByName(host));
		try {
			return lookup.get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
		finally {
			// A blocked DNS call does not respond to interruption, so this only stops us
			// waiting — the carrier thread finishes on its own. It is a virtual thread,
			// so leaving it parked costs a few hundred bytes rather than a stack.
			lookup.cancel(true);
		}
	}

	/**
	 * Every range the public internet cannot route to, plus the ones that are routable
	 * but should never be a Destination.
	 */
	static boolean isPrivate(InetAddress address) {
		if (address.isLoopbackAddress()      // 127/8, ::1
				|| address.isLinkLocalAddress()  // 169.254/16 (incl. cloud metadata), fe80::/10
				|| address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
				|| address.isAnyLocalAddress()   // 0.0.0.0, ::
				|| address.isMulticastAddress()) {
			return true;
		}

		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			int first = bytes[0] & 0xFF;
			int second = bytes[1] & 0xFF;
			// 100.64/10 — carrier-grade NAT. Routable in theory, never a destination.
			return first == 100 && second >= 64 && second <= 127;
		}
		// fc00::/7 — IPv6 unique local. Java's isSiteLocalAddress only covers the
		// deprecated fec0::/10, so this range needs checking by hand.
		return (bytes[0] & 0xFE) == 0xFC;
	}
}
