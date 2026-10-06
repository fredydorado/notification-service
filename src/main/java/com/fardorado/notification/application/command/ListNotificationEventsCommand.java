package com.fardorado.notification.application.command;

import java.time.Instant;

import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * Request to list the notification events owned by one client.
 *
 * <p>{@code clientId} is supplied by the calling adapter from the
 * authenticated caller, never from a client-controlled query parameter.</p>
 *
 * @param from           inclusive lower bound on creation time, or {@code null}
 * @param to             inclusive upper bound on creation time, or {@code null}
 * @param deliveryStatus status filter, or {@code null} for every status
 * @param page           zero-based page number
 * @param size           page size
 */
public record ListNotificationEventsCommand(
        String clientId,
        Instant from,
        Instant to,
        NotificationEventStatus deliveryStatus,
        int page,
        int size) {
}
