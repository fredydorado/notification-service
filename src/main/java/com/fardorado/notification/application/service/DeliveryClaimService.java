package com.fardorado.notification.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;
import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.configuration.NotificationProcessingProperties;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.subscription.Subscription;

/**
 * Claims notification events that are due for delivery work and prepares
 * them for execution, all inside a single short database transaction:
 *
 * <ul>
 *   <li>{@code PENDING} / due {@code RETRY_SCHEDULED} events are transitioned
 *       to {@code DELIVERING} with a new delivery attempt created
 *       (append-only history), and a {@link ProcessDeliveryCommand} is
 *       returned for the worker pool;</li>
 *   <li>stale {@code DELIVERING} events (lease exceeded, e.g. after a crash)
 *       are recovered: their {@code IN_PROGRESS} attempts are failed and the
 *       retry policy decides between {@code RETRY_SCHEDULED} and
 *       {@code FAILED}. Stale events are never silently completed and are
 *       not redelivered within the same claim.</li>
 * </ul>
 *
 * <p>Row claiming uses {@code FOR UPDATE SKIP LOCKED} (see
 * {@link NotificationEventRepository#claimDeliverable}), so multiple dispatcher
 * instances or workers never prepare the same event concurrently.</p>
 */
@Component
@RequiredArgsConstructor
public class DeliveryClaimService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryClaimService.class);

    private final NotificationEventRepository notificationEventRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final RetryPolicy retryPolicy;
    private final NotificationProcessingProperties properties;
    private final Clock clock;

    @Transactional
    public List<ProcessDeliveryCommand> claimDeliveries(int limit) {
        Instant now = clock.instant();
        Instant staleThreshold = now.minus(properties.leaseTimeout());
        return notificationEventRepository.claimDeliverable(limit, now, staleThreshold).stream()
                .map(this::prepareClaimedEvent)
                .filter(Objects::nonNull)
                .toList();
    }

    private ProcessDeliveryCommand prepareClaimedEvent(NotificationEvent event) {
        return switch (event.getStatus()) {
            case PENDING, RETRY_SCHEDULED -> startDelivery(event);
            case DELIVERING -> recoverStaleDelivery(event);
            case COMPLETED, FAILED -> null;
        };
    }

    private ProcessDeliveryCommand startDelivery(NotificationEvent event) {
        event.markDelivering();
        event = notificationEventRepository.save(event);

        Optional<Subscription> subscription =
                event.getSubscriptionId() == null
                        ? Optional.empty()
                        : subscriptionRepository.findById(event.getSubscriptionId());
        if (subscription.isEmpty()) {
            log.error(
                    "subscription not found for delivery: eventId={}, notificationEventId={}, subscriptionId={}",
                    event.getEventId(), event.getId(), event.getSubscriptionId());
            event.markFailed();
            notificationEventRepository.save(event);
            return null;
        }

        int attemptNumber = deliveryAttemptRepository.nextAttemptNumber(event.getId());
        DeliveryAttempt attempt = deliveryAttemptRepository.save(
                DeliveryAttempt.startAttempt(event.getId(), attemptNumber));
        log.info(
                "delivery attempt created: eventId={}, notificationEventId={}, deliveryAttemptId={}, attemptNumber={}",
                event.getEventId(), event.getId(), attempt.getId(), attemptNumber);

        return new ProcessDeliveryCommand(
                event.getId(),
                attempt.getId(),
                subscription.get().getWebhookUrl(),
                event.getEventId(),
                event.getEventType(),
                event.getEventVersion(),
                event.getCorrelationId(),
                event.getPayload(),
                attemptNumber);
    }

    private ProcessDeliveryCommand recoverStaleDelivery(NotificationEvent event) {
        Instant now = clock.instant();
        List<DeliveryAttempt> staleAttempts = deliveryAttemptRepository
                .findByNotificationEventIdAndStatus(event.getId(), DeliveryAttemptStatus.IN_PROGRESS);
        for (DeliveryAttempt attempt : staleAttempts) {
            attempt.markFailed("stale delivery recovered after exceeding the delivery lease", null, now);
            deliveryAttemptRepository.save(attempt);
            log.warn(
                    "stale delivery recovered: eventId={}, notificationEventId={}, deliveryAttemptId={}, attemptNumber={}",
                    event.getEventId(), event.getId(), attempt.getId(), attempt.getAttemptNumber());
        }

        int lastAttemptNumber = staleAttempts.stream()
                .mapToInt(DeliveryAttempt::getAttemptNumber)
                .max()
                .orElse(0);
        if (retryPolicy.isExhausted(lastAttemptNumber)) {
            event.markFailed();
            notificationEventRepository.save(event);
            log.warn(
                    "notification failed permanently after stale delivery recovery: eventId={}, notificationEventId={}, attemptNumber={}",
                    event.getEventId(), event.getId(), lastAttemptNumber);
        } else {
            event.scheduleRetry(retryPolicy.nextAttemptAt(lastAttemptNumber, now));
            notificationEventRepository.save(event);
            log.info(
                    "retry scheduled after stale delivery recovery: eventId={}, notificationEventId={}, nextAttemptAt={}",
                    event.getEventId(), event.getId(), event.getNextAttemptAt());
        }
        // A recovered event is never redelivered within the same claim; it is
        // redelivered when its next attempt time arrives.
        return null;
    }
}
