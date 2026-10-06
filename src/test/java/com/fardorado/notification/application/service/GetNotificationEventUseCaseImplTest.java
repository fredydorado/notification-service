package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.application.command.GetNotificationEventCommand;
import com.fardorado.notification.application.exception.ResourceNotFoundException;
import com.fardorado.notification.application.result.NotificationEventDetailResult;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

class GetNotificationEventUseCaseImplTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-10-05T15:30:00Z");

    private final FakeNotificationEventRepository notificationEventRepository =
            new FakeNotificationEventRepository();
    private final FakeDeliveryAttemptRepository deliveryAttemptRepository =
            new FakeDeliveryAttemptRepository();
    private final FakeSubscriptionRepository subscriptionRepository = new FakeSubscriptionRepository();

    private final GetNotificationEventUseCaseImpl useCase = new GetNotificationEventUseCaseImpl(
            notificationEventRepository, deliveryAttemptRepository, subscriptionRepository);

    @Test
    void shouldComposeDetailsFromEventSubscriptionAndAttempts() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        NotificationEvent event = persistEvent("evt-1", "client-1", subscription.getId());
        failedAttempt(event.getId(), 1, 500);
        failedAttempt(event.getId(), 2, 503);

        NotificationEventDetailResult result =
                useCase.getNotificationEvent(new GetNotificationEventCommand("client-1", "evt-1"));

        assertThat(result.id()).isEqualTo("evt-1");
        assertThat(result.eventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(result.webhookUrl()).isEqualTo(subscription.getWebhookUrl());
        assertThat(result.attemptCount()).isEqualTo(2);
        // The last attempt's status, not the highest or the first.
        assertThat(result.lastHttpStatus()).isEqualTo(503);
        assertThat(result.attempts())
                .extracting(attempt -> attempt.attempt(), attempt -> attempt.httpStatus())
                .containsExactly(tuple(1, 500), tuple(2, 503));
    }

    @Test
    void shouldReportNoAttemptsAsZeroCountAndNullHttpStatus() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        persistEvent("evt-fresh", "client-1", subscription.getId());

        NotificationEventDetailResult result =
                useCase.getNotificationEvent(new GetNotificationEventCommand("client-1", "evt-fresh"));

        assertThat(result.attemptCount()).isZero();
        assertThat(result.lastHttpStatus()).isNull();
        assertThat(result.attempts()).isEmpty();
    }

    @Test
    void shouldKeepNullHttpStatusWhenTheLastAttemptGotNoResponse() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        NotificationEvent event = persistEvent("evt-timeout", "client-1", subscription.getId());
        failedAttempt(event.getId(), 1, 500);
        failedAttempt(event.getId(), 2, null);

        NotificationEventDetailResult result =
                useCase.getNotificationEvent(new GetNotificationEventCommand("client-1", "evt-timeout"));

        assertThat(result.lastHttpStatus()).isNull();
    }

    @Test
    void shouldNotFindAnotherClientsEvent() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-2", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        persistEvent("evt-theirs", "client-2", subscription.getId());

        assertThatThrownBy(() -> useCase.getNotificationEvent(
                new GetNotificationEventCommand("client-1", "evt-theirs")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldNotFindAnUnknownEvent() {
        assertThatThrownBy(() -> useCase.getNotificationEvent(
                new GetNotificationEventCommand("client-1", "evt-unknown")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private NotificationEvent persistEvent(String eventId, String clientId, Long subscriptionId) {
        NotificationEvent saved = notificationEventRepository.save(NotificationEvent.newEvent(
                eventId, EventType.CREDIT_CARD_PAYMENT, 1, null, "{}", subscriptionId));
        notificationEventRepository.stageOwnership(eventId, clientId);
        return saved;
    }

    private void failedAttempt(Long notificationEventId, int attemptNumber, Integer httpStatus) {
        DeliveryAttempt attempt = DeliveryAttempt.startAttempt(notificationEventId, attemptNumber);
        attempt.markFailed("webhook endpoint returned an error", httpStatus, COMPLETED_AT);
        DeliveryAttempt saved = deliveryAttemptRepository.save(attempt);
        assertThat(saved.getStatus()).isEqualTo(DeliveryAttemptStatus.FAILED);
    }
}
