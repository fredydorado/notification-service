package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.application.command.ProcessNotificationEventCommand;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

class ProcessNotificationEventUseCaseImplTest {

    private final FakeNotificationEventRepository notificationEventRepository =
            new FakeNotificationEventRepository();
    private final FakeSubscriptionRepository subscriptionRepository = new FakeSubscriptionRepository();

    private final ProcessNotificationEventUseCaseImpl useCase = new ProcessNotificationEventUseCaseImpl(
            notificationEventRepository,
            new SubscriptionMatcher(subscriptionRepository));

    @Test
    void shouldPersistNewEventAsPendingWithMatchedSubscription() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);

        useCase.processNotificationEvent(command("evt-1", EventType.CREDIT_CARD_PAYMENT, "client-1"));

        var saved = notificationEventRepository.findByEventId("evt-1").orElseThrow();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(NotificationEventStatus.PENDING);
        assertThat(saved.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(saved.getEventVersion()).isEqualTo(2);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-1");
        assertThat(saved.getPayload()).isEqualTo("payment received");
    }

    @Test
    void shouldPersistStructuredContentVerbatim() {
        subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);

        useCase.processNotificationEvent(
                new ProcessNotificationEventCommand("evt-json", EventType.CREDIT_CARD_PAYMENT, 1,
                        null, "{\"amount\": 150}", "client-1"));

        var saved = notificationEventRepository.findByEventId("evt-json").orElseThrow();
        assertThat(saved.getPayload()).isEqualTo("{\"amount\": 150}");
    }

    @Test
    void shouldNotDuplicateProcessingForRepeatedEventId() {
        subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);

        useCase.processNotificationEvent(command("evt-1", EventType.CREDIT_CARD_PAYMENT, "client-1"));
        useCase.processNotificationEvent(command("evt-1", EventType.CREDIT_CARD_PAYMENT, "client-1"));
        useCase.processNotificationEvent(command("evt-1", EventType.CREDIT_CARD_PAYMENT, "client-1"));

        assertThat(notificationEventRepository.savedEventIds())
                .containsExactly("evt-1");
    }

    @Test
    void shouldFailEventWithoutActiveSubscription() {
        subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.INACTIVE);

        useCase.processNotificationEvent(command("evt-no-sub", EventType.CREDIT_CARD_PAYMENT, "client-1"));

        var saved = notificationEventRepository.findByEventId("evt-no-sub").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(NotificationEventStatus.FAILED);
        assertThat(saved.getSubscriptionId()).isNull();
    }

    @Test
    void shouldFailEventForUnknownClient() {
        useCase.processNotificationEvent(command("evt-unknown", EventType.CREDIT_TRANSFER, "client-x"));

        var saved = notificationEventRepository.findByEventId("evt-unknown").orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(NotificationEventStatus.FAILED);
        assertThat(saved.getSubscriptionId()).isNull();
    }

    @Test
    void shouldIgnoreInactiveSubscriptionAndMatchActiveOneOfDifferentClient() {
        subscriptionRepository.addSubscription(
                "client-a", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.INACTIVE);
        Subscription active = subscriptionRepository.addSubscription(
                "client-b", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);

        useCase.processNotificationEvent(command("evt-a", EventType.CREDIT_CARD_PAYMENT, "client-a"));
        useCase.processNotificationEvent(command("evt-b", EventType.CREDIT_CARD_PAYMENT, "client-b"));

        assertThat(notificationEventRepository.findByEventId("evt-a").orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.FAILED);
        assertThat(notificationEventRepository.findByEventId("evt-b").orElseThrow().getSubscriptionId())
                .isEqualTo(active.getId());
    }

    private ProcessNotificationEventCommand command(String eventId, EventType eventType, String clientId) {
        return new ProcessNotificationEventCommand(eventId, eventType, 2, "corr-1",
                "payment received", clientId);
    }
}
