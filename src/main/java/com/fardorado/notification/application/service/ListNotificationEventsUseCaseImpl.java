package com.fardorado.notification.application.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.application.command.ListNotificationEventsCommand;
import com.fardorado.notification.application.exception.InvalidRequestException;
import com.fardorado.notification.application.port.in.ListNotificationEventsUseCase;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.result.NotificationEventSummaryResult;
import com.fardorado.notification.application.result.PagedResult;
import com.fardorado.notification.configuration.NotificationApiProperties;

/**
 * Lists the notification events owned by the calling client.
 *
 * <p>Ownership is enforced here rather than in the adapter: the query is
 * always scoped to the client on the command, and the API never accepts a
 * client identifier as a request parameter.</p>
 */
@Component
public class ListNotificationEventsUseCaseImpl implements ListNotificationEventsUseCase {

    private final NotificationEventRepository notificationEventRepository;
    private final NotificationApiProperties properties;

    public ListNotificationEventsUseCaseImpl(
            NotificationEventRepository notificationEventRepository,
            NotificationApiProperties properties) {
        this.notificationEventRepository = notificationEventRepository;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResult<NotificationEventSummaryResult> listNotificationEvents(
            ListNotificationEventsCommand command) {
        validate(command);
        return notificationEventRepository.findSummariesByClientId(
                command.clientId(),
                command.from(),
                command.to(),
                command.deliveryStatus(),
                command.page(),
                command.size());
    }

    /**
     * Cross-field and configuration-dependent rules that bean validation on
     * the request parameters cannot express.
     */
    private void validate(ListNotificationEventsCommand command) {
        if (command.from() != null && command.to() != null && command.from().isAfter(command.to())) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
        if (command.size() > properties.maxPageSize()) {
            throw new InvalidRequestException(
                    "'size' must not exceed %d".formatted(properties.maxPageSize()));
        }
    }
}
