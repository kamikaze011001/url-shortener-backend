package com.sonanh.urlshortener.identity.domain;

import com.sonanh.urlshortener.shared.mail.MailSender.MailMessage;

/**
 * The words that go in the two emails this service sends.
 *
 * <p>Here rather than inline in the use cases so that both messages can be read side by
 * side. They are the only prose a user sees outside the app, and prose that lives in
 * string literals scattered across two files drifts apart.
 *
 * <p>No links. Both flows ask for a code to be typed back in, which means an intercepted
 * email cannot be acted on by clicking, and there is no URL for a mail client to rewrite
 * or a scanner to visit — link-scanning proxies routinely consume single-use links
 * before the human ever sees them.
 */
public final class CodeMessages {

	private CodeMessages() {
	}

	public static MailMessage verification(String to, String code) {
		return new MailMessage(to, "Confirm your email address", """
				Your confirmation code is %s

				It expires in %d minutes and can be used once.

				If you did not create an account, ignore this message — nothing was
				created that needs undoing, and the address will not be used again.
				""".formatted(code, CodeVerdict.TTL.toMinutes()));
	}

	public static MailMessage passwordReset(String to, String code) {
		return new MailMessage(to, "Reset your password", """
				Your password reset code is %s

				It expires in %d minutes and can be used once.

				Setting a new password signs you out everywhere else.

				If you did not ask for this, you can ignore it. Your password has not
				changed, and nobody can change it without this code.
				""".formatted(code, CodeVerdict.TTL.toMinutes()));
	}
}
