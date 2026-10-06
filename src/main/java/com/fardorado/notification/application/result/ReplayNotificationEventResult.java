package com.fardorado.notification.application.result;

import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * Acknowledgement that a replay has been accepted. The status is the event's
 * state immediately after the replay transition; the existing dispatch
 * infrastructure performs the actual reprocessing asynchronously.
 */
public record ReplayNotificationEventResult(
        String notificationEventId,
        NotificationEventStatus status) {
}
