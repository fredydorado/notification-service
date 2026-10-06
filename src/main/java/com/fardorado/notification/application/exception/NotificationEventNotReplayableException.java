package com.fardorado.notification.application.exception;

import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * Replay was requested for a notification event that is not in the terminal
 * {@link NotificationEventStatus#FAILED} state. Only definitively failed
 * events may be replayed.
 */
public class NotificationEventNotReplayableException extends ResourceConflictException {

    public NotificationEventNotReplayableException(String eventId, NotificationEventStatus status) {
        super(ErrorCode.NOTIFICATION_EVENT_NOT_REPLAYABLE,
                "Notification event %s is %s and cannot be replayed; only FAILED events are replayable"
                        .formatted(eventId, status));
    }
}
