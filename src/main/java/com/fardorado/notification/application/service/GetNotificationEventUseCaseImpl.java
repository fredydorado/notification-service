package com.fardorado.notification.application.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.application.command.GetNotificationEventCommand;
import com.fardorado.notification.application.exception.ResourceNotFoundException;
import com.fardorado.notification.application.port.in.GetNotificationEventUseCase;
import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.application.result.DeliveryAttemptResult;
import com.fardorado.notification.application.result.NotificationEventDetailResult;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.subscription.Subscription;

/**
 * Retrieves one notification event owned by the calling client, composed from
 * the existing event, subscription and delivery-attempt ports.
 */
@Component
public class GetNotificationEventUseCaseImpl implements GetNotificationEventUseCase {

    private final NotificationEventRepository notificationEventRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final SubscriptionRepository subscriptionRepository;

    public GetNotificationEventUseCaseImpl(
            NotificationEventRepository notificationEventRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            SubscriptionRepository subscriptionRepository) {
        this.notificationEventRepository = notificationEventRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationEventDetailResult getNotificationEvent(GetNotificationEventCommand command) {
        NotificationEvent event = notificationEventRepository
                .findByEventIdAndClientId(command.eventId(), command.clientId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification event %s not found".formatted(command.eventId())));

        List<DeliveryAttempt> attempts = deliveryAttemptRepository
                .findByNotificationEventIdOrderByAttemptNumber(event.getId());

        // An event is only visible to a client through its subscription, so
        // subscriptionId is set here in practice; the guard keeps the lookup
        // safe if that ever stops holding.
        String webhookUrl = event.getSubscriptionId() == null
                ? null
                : subscriptionRepository.findById(event.getSubscriptionId())
                        .map(Subscription::getWebhookUrl)
                        .orElse(null);

        return new NotificationEventDetailResult(
                event.getEventId(),
                event.getEventType(),
                event.getCreatedAt(),
                event.getStatus(),
                attempts.size(),
                lastHttpStatusOf(attempts),
                webhookUrl,
                attempts.stream()
                        .map(attempt -> new DeliveryAttemptResult(
                                attempt.getAttemptNumber(), attempt.getStatus(), attempt.getHttpStatus()))
                        .toList());
    }

    /**
     * The HTTP status of the most recent attempt, which is {@code null} when
     * that attempt never received a response.
     */
    private Integer lastHttpStatusOf(List<DeliveryAttempt> attempts) {
        return attempts.stream()
                .max(Comparator.comparingInt(DeliveryAttempt::getAttemptNumber))
                .map(DeliveryAttempt::getHttpStatus)
                .orElse(null);
    }
}
