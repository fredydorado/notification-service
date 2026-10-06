package com.fardorado.notification.application.port.in;

import com.fardorado.notification.application.command.GetNotificationEventCommand;
import com.fardorado.notification.application.result.NotificationEventDetailResult;

/**
 * Retrieves one notification event owned by the calling client, together with
 * its delivery-attempt history.
 */
public interface GetNotificationEventUseCase {

    NotificationEventDetailResult getNotificationEvent(GetNotificationEventCommand command);
}
