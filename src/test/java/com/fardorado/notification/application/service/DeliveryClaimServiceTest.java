package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;
import com.fardorado.notification.configuration.NotificationProcessingProperties;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

class DeliveryClaimServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeNotificationEventRepository notificationEventRepository =
            new FakeNotificationEventRepository();
    private final FakeSubscriptionRepository subscriptionRepository = new FakeSubscriptionRepository();
    private final FakeDeliveryAttemptRepository deliveryAttemptRepository =
            new FakeDeliveryAttemptRepository();

    private final DeliveryClaimService claimService = new DeliveryClaimService(
            notificationEventRepository,
            deliveryAttemptRepository,
            subscriptionRepository,
            new RetryPolicy(properties()),
            properties(),
            CLOCK);

    @Test
    void shouldPreparePendingEventForDelivery() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        NotificationEvent pending = persist(
                "evt-1", NotificationEventStatus.PENDING, subscription.getId(), null);
        notificationEventRepository.stageClaimable(pending);

        List<ProcessDeliveryCommand> commands = claimService.claimDeliveries(10);

        assertThat(commands).hasSize(1);
        ProcessDeliveryCommand command = commands.getFirst();
        assertThat(command.notificationEventId()).isEqualTo(pending.getId());
        assertThat(command.webhookUrl()).isEqualTo(subscription.getWebhookUrl());
        assertThat(command.eventId()).isEqualTo("evt-1");
        assertThat(command.attemptNumber()).isEqualTo(1);
        assertThat(command.payload()).isEqualTo("content-evt-1");

        assertThat(notificationEventRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.DELIVERING);
        DeliveryAttempt attempt = deliveryAttemptRepository.findById(command.deliveryAttemptId())
                .orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.IN_PROGRESS);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void shouldCreateNextAttemptNumberForRetry() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        NotificationEvent retryable = persist(
                "evt-2", NotificationEventStatus.RETRY_SCHEDULED, subscription.getId(), null);
        DeliveryAttempt first = deliveryAttemptRepository.save(
                DeliveryAttempt.startAttempt(retryable.getId(), 1));
        first.markFailed("webhook endpoint returned HTTP 500", 500, NOW);
        deliveryAttemptRepository.save(first);
        notificationEventRepository.stageClaimable(retryable);

        List<ProcessDeliveryCommand> commands = claimService.claimDeliveries(10);

        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().attemptNumber()).isEqualTo(2);
        assertThat(deliveryAttemptRepository.findByNotificationEventIdOrderByAttemptNumber(
                        retryable.getId()))
                .extracting(DeliveryAttempt::getAttemptNumber)
                .containsExactly(1, 2);
    }

    @Test
    void shouldFailEventWhenSubscriptionVanished() {
        NotificationEvent pending = persist(
                "evt-3", NotificationEventStatus.PENDING, 999L, null);
        notificationEventRepository.stageClaimable(pending);

        List<ProcessDeliveryCommand> commands = claimService.claimDeliveries(10);

        assertThat(commands).isEmpty();
        assertThat(notificationEventRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.FAILED);
    }

    @Test
    void shouldRecoverStaleDeliveringEventAsRetryScheduled() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        NotificationEvent stale = persist(
                "evt-4", NotificationEventStatus.DELIVERING, subscription.getId(), null);
        DeliveryAttempt inProgress = deliveryAttemptRepository.save(
                DeliveryAttempt.startAttempt(stale.getId(), 1));
        notificationEventRepository.stageClaimable(stale);

        List<ProcessDeliveryCommand> commands = claimService.claimDeliveries(10);

        // A recovered event is never redelivered within the same claim.
        assertThat(commands).isEmpty();
        assertThat(deliveryAttemptRepository.findById(inProgress.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(deliveryAttemptRepository.findById(inProgress.getId()).orElseThrow()
                        .getErrorMessage())
                .contains("stale delivery recovered");
        NotificationEvent recovered =
                notificationEventRepository.findById(stale.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(NotificationEventStatus.RETRY_SCHEDULED);
        assertThat(recovered.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void shouldFailStaleDeliveringEventWhenRetriesExhausted() {
        Subscription subscription = subscriptionRepository.addSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);
        NotificationEvent stale = persist(
                "evt-5", NotificationEventStatus.DELIVERING, subscription.getId(), null);
        DeliveryAttempt last = deliveryAttemptRepository.save(
                DeliveryAttempt.startAttempt(stale.getId(), 5));
        notificationEventRepository.stageClaimable(stale);

        List<ProcessDeliveryCommand> commands = claimService.claimDeliveries(10);

        assertThat(commands).isEmpty();
        assertThat(deliveryAttemptRepository.findById(last.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(notificationEventRepository.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.FAILED);
    }

    private NotificationEvent persist(
            String eventId,
            NotificationEventStatus status,
            Long subscriptionId,
            Instant nextAttemptAt) {
        NotificationEvent event = NotificationEvent.newEvent(
                eventId, EventType.CREDIT_CARD_PAYMENT, 1, null, "content-" + eventId, subscriptionId);
        if (status != NotificationEventStatus.PENDING) {
            event = new NotificationEvent(
                    null, eventId, EventType.CREDIT_CARD_PAYMENT, 1, null,
                    status, "content-" + eventId, subscriptionId, nextAttemptAt, null, null);
        }
        return notificationEventRepository.save(event);
    }

    private NotificationProcessingProperties properties() {
        return new NotificationProcessingProperties(
                5, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(5), 50, 10);
    }
}
