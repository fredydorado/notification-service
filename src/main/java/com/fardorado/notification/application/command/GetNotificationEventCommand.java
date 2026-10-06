package com.fardorado.notification.application.command;

/**
 * Request for one notification event's details.
 *
 * @param clientId the authenticated caller; ownership is enforced against it
 * @param eventId  the canonical event identity ({@code event_id})
 */
public record GetNotificationEventCommand(String clientId, String eventId) {
}
