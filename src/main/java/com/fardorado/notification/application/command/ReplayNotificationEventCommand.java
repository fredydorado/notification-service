package com.fardorado.notification.application.command;

/**
 * Request to replay a definitively failed notification event.
 *
 * @param clientId the authenticated caller; ownership is enforced against it
 * @param eventId  the canonical event identity ({@code event_id})
 */
public record ReplayNotificationEventCommand(String clientId, String eventId) {
}
