package com.fardorado.notification.application.port.in;

import com.fardorado.notification.application.command.ListNotificationEventsCommand;
import com.fardorado.notification.application.result.NotificationEventSummaryResult;
import com.fardorado.notification.application.result.PagedResult;

/**
 * Lists the notification events owned by the calling client, filtered by
 * creation date range and delivery status.
 */
public interface ListNotificationEventsUseCase {

    PagedResult<NotificationEventSummaryResult> listNotificationEvents(ListNotificationEventsCommand command);
}
