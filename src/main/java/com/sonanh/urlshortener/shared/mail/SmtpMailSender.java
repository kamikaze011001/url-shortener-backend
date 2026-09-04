package com.sonanh.urlshortener.shared.mail;

import com.sonanh.urlshortener.shared.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP, pointed wherever configuration says — Mailpit on a laptop, a provider in
 * production. The distinction exists in {@code application.yml} and nowhere in Java.
 *
 * <p>Plain text, not HTML. A six-digit code needs no markup, and an HTML mail needs a
 * text alternative, a template engine and a rendering step to go wrong in.
 */
@Component
class SmtpMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

	private final JavaMailSender smtp;
	private final AppProperties properties;
	private final MeterRegistry meters;

	SmtpMailSender(JavaMailSender smtp, AppProperties properties, MeterRegistry meters) {
		this.smtp = smtp;
		this.properties = properties;
		this.meters = meters;
	}

	@Override
	public void send(MailMessage message) {
		var mail = new SimpleMailMessage();
		mail.setFrom(properties.mail().from());
		mail.setTo(message.to());
		mail.setSubject(message.subject());
		mail.setText(message.body());

		try {
			smtp.send(mail);
			meters.counter("urlshortener.mail.send", "outcome", "sent").increment();
		}
		catch (MailException ex) {
			meters.counter("urlshortener.mail.send", "outcome", "failed").increment();

			// The recipient is not logged. An address plus a timestamp plus "password
			// reset" is exactly the correlation 02-nfr.md keeps out of the logs.
			log.warn("mail.send_failed subject={} reason={}", message.subject(), ex.toString());

			throw new MailFailure("Could not send mail", ex);
		}
	}
}
