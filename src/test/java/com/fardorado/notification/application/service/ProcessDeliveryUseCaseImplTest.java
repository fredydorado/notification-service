package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;
import com.fardorado.notification.application.port.out.NotificationChannelClient;
import com.fardorado.notification.application.port.out.WebhookDeliveryCommand;
import com.fardorado.notification.application.port.out.WebhookDeliveryResult;
import com.fardorado.notification.configuration.NotificationProcessingProperties;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

class ProcessDeliveryUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeNotificationEventRepository notificationEventRepository =
            new FakeNotificationEventRepository();
    private final FakeSubscriptionRepository subscriptionRepository = new FakeSubscriptionRepository();
    private final FakeDeliveryAttemptRepository deliveryAttemptRepository =
            new FakeDeliveryAttemptRepository();

    private final AtomicReference<WebhookDeliveryResult> nextResult =
            new AtomicReference<>(WebhookDeliveryResult.success());

    private final NotificationChannelClient channelClient = new NotificationChannelClient() {
        @Override
        public WebhookDeliveryResult deliver(WebhookDeliveryCommand command) {
            WebhookDeliveryResult result = nextResult.get();
            if (result == null) {
                throw new IllegalStateException("simulated unexpected worker failure");
            }
            return result;
        }
    };

    private final NotificationProcessingProperties properties = new NotificationProcessingProperties(
            2, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(5), 50, 10);

    private final DeliveryResultService resultService = new DeliveryResultService(
            notificationEventRepository,
            deliveryAttemptRepository,
            new RetryPolicy(properties),
            CLOCK);

    private final ProcessDeliveryUseCaseImpl useCase = new ProcessDeliveryUseCaseImpl(
            channelClient, resultService);

    @Test
    void successfulDeliveryCompletesEventAndAttempt() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        ClaimedDelivery claimed = claimDelivery(subscription, 1);
        nextResult.set(WebhookDeliveryResult.success());

        useCase.processDelivery(claimed.command());

        assertThat(notificationEventRepository.findById(claimed.eventId()).orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.COMPLETED);
        DeliveryAttempt attempt =
                deliveryAttemptRepository.findById(claimed.attemptId()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.SUCCESS);
        assertThat(attempt.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void retryableFailureSchedulesRetry() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        ClaimedDelivery claimed = claimDelivery(subscription, 1);
        nextResult.set(WebhookDeliveryResult.retryableFailure("webhook endpoint returned HTTP 500"));

        useCase.processDelivery(claimed.command());

        NotificationEvent event =
                notificationEventRepository.findById(claimed.eventId()).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.RETRY_SCHEDULED);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(deliveryAttemptRepository.findById(claimed.attemptId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryAttemptStatus.FAILED);
    }

    @Test
    void retryableFailureWithExhaustedRetriesFailsEvent() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        ClaimedDelivery claimed = claimDelivery(subscription, 2);
        nextResult.set(WebhookDeliveryResult.retryableFailure("webhook endpoint returned HTTP 500"));

        useCase.processDelivery(claimed.command());

        NotificationEvent event =
                notificationEventRepository.findById(claimed.eventId()).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.FAILED);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(deliveryAttemptRepository.findById(claimed.attemptId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryAttemptStatus.FAILED);
    }

    @Test
    void permanentFailureFailsEvent() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        ClaimedDelivery claimed = claimDelivery(subscription, 1);
        nextResult.set(WebhookDeliveryResult.permanentFailure("webhook endpoint returned HTTP 404"));

        useCase.processDelivery(claimed.command());

        assertThat(notificationEventRepository.findById(claimed.eventId()).orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.FAILED);
        DeliveryAttempt attempt =
                deliveryAttemptRepository.findById(claimed.attemptId()).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.getErrorMessage())
                .isEqualTo("webhook endpoint returned HTTP 404");
    }

    @Test
    void unexpectedWorkerFailureIsClassifiedAsRetryable() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        ClaimedDelivery claimed = claimDelivery(subscription, 1);
        nextResult.set(null);

        assertThatCode(() -> useCase.processDelivery(claimed.command()))
                .doesNotThrowAnyException();

        NotificationEvent event =
                notificationEventRepository.findById(claimed.eventId()).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.RETRY_SCHEDULED);
        assertThat(deliveryAttemptRepository.findById(claimed.attemptId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryAttemptStatus.FAILED);
    }

    @Test
    void optimisticLockingConflictDoesNotOverwriteNewerState() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        ClaimedDelivery claimed = claimDelivery(subscription, 1);
        nextResult.set(WebhookDeliveryResult.success());

        // A concurrent transition (e.g. a stale-delivery recovery) modifies the
        // event first; the worker's stale update must not overwrite it.
        NotificationEvent concurrentlyUpdated =
                notificationEventRepository.findById(claimed.eventId()).orElseThrow();
        notificationEventRepository.onSave =
                event -> {
                    if (event.getId().equals(concurrentlyUpdated.getId())
                            && event.getVersion() < concurrentlyUpdated.getVersion()) {
                        throw new OptimisticLockingFailureException("stale state");
                    }
                    return event;
                };

        assertThatCode(() -> useCase.processDelivery(claimed.command()))
                .doesNotThrowAnyException();

        Optional<NotificationEvent> current =
                notificationEventRepository.findById(claimed.eventId());
        assertThat(current).isPresent();
    }

    private ClaimedDelivery claimDelivery(Subscription subscription, int attemptNumber) {
        NotificationEvent event = NotificationEvent.newEvent(
                "evt-" + System.nanoTime(),
                EventType.CREDIT_CARD_PAYMENT,
                1,
                null,
                "content",
                subscription.getId());
        event.markDelivering();
        event = notificationEventRepository.save(event);
        DeliveryAttempt attempt = deliveryAttemptRepository.save(
                DeliveryAttempt.startAttempt(event.getId(), attemptNumber));
        ProcessDeliveryCommand command = new ProcessDeliveryCommand(
                event.getId(),
                attempt.getId(),
                subscription.getWebhookUrl(),
                event.getEventId(),
                event.getEventType(),
                event.getEventVersion(),
                event.getCorrelationId(),
                event.getPayload(),
                attemptNumber);
        return new ClaimedDelivery(event.getId(), attempt.getId(), command);
    }

    private record ClaimedDelivery(Long eventId, Long attemptId, ProcessDeliveryCommand command) {
    }
}
