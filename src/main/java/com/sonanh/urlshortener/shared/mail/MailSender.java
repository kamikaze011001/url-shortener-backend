package com.sonanh.urlshortener.shared.mail;

/**
 * The seam between this service and however mail actually leaves it.
 *
 * <p>Today one SMTP implementation, pointed at Mailpit locally and a provider in
 * production by configuration alone. Tomorrow it could be an HTTP API client, or a row
 * written to an outbox table for a worker to drain. <b>Nothing that sends mail changes
 * at any of those steps</b> — the same return this project already takes on
 * {@code ClickRecorder} and {@code DestinationScreener}.
 *
 * <p><b>Unlike {@code ClickRecorder}, this one throws.</b> A Click that goes unrecorded
 * is invisible and harmless; a code that never arrives leaves someone locked out of
 * their account, staring at a form. The caller has to know, so the failure is not
 * swallowed here.
 *
 * <p>What each caller then does with that failure is a separate decision, and they do
 * not all make the same one — see {@code RegisterOwnerUseCase} for the case where the
 * send fails and the request should still succeed.
 */
public interface MailSender {

	/**
	 * @throws MailFailure when the message could not be handed off. Delivery is still
	 *                     not guaranteed after a successful return: SMTP acceptance
	 *                     means the next hop took it, not that anyone read it.
	 */
	void send(MailMessage message);

	record MailMessage(String to, String subject, String body) {}

	class MailFailure extends RuntimeException {
		public MailFailure(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
