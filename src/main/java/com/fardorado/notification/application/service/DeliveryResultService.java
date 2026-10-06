package com.fardorado.notification.application.service;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;
import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.port.out.WebhookDeliveryResult;
import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.notification.NotificationEvent;

/**
 * Records the outcome of one external delivery attempt in a short database
 * transaction: the delivery attempt reaches {@code SUCCESS}/{@code FAILED}
 * and the notification event transitions according to the result and the
 * retry policy ({@code COMPLETED}, {@code RETRY_SCHEDULED} or
 * {@code FAILED}).
 *
 * <p>This runs after the external webhook call has finished, so no database
 * transaction is ever held open around external I/O. Optimistic-locking and
 * state-transition conflicts (e.g. a concurrent stale-delivery recovery
 * transitioned the same rows first) are treated exactly as the design
 * requires: the newer state is authoritative, nothing is overwritten and no
 * duplicate delivery work is created.</p>
 */
@Component
public class DeliveryResultService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryResultService.class);

    private final NotificationEventRepository notificationEventRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public DeliveryResultService(
            NotificationEventRepository notificationEventRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            RetryPolicy retryPolicy,
            Clock clock) {
        this.notificationEventRepository = notificationEventRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public void recordDeliveryResult(ProcessDeliveryCommand command, WebhookDeliveryResult result) {
        NotificationEvent event = notificationEventRepository
                .findById(command.notificationEventId())
                .orElse(null);
        DeliveryAttempt attempt = deliveryAttemptRepository
                .findById(command.deliveryAttemptId())
                .orElse(null);
        if (event == null || attempt == null) {
            log.error(
                    "delivery result cannot be recorded, entities not found: eventId={}, notificationEventId={}, deliveryAttemptId={}",
                    command.eventId(), command.notificationEventId(), command.deliveryAttemptId());
            return;
        }

        Instant completedAt = clock.instant();
        try {
            switch (result.outcome()) {
                case SUCCESS -> {
                    attempt.markSuccess(result.httpStatus(), completedAt);
                    event.markCompleted();
                    log.info(
                            "delivery succeeded: eventId={}, notificationEventId={}, deliveryAttemptId={}, attemptNumber={}",
                            command.eventId(), command.notificationEventId(), command.deliveryAttemptId(),
                            command.attemptNumber());
                }
                case RETRYABLE_FAILURE -> {
                    attempt.markFailed(result.errorMessage(), result.httpStatus(), completedAt);
                    if (retryPolicy.isExhausted(command.attemptNumber())) {
                        event.markFailed();
                        log.warn(
                                "notification failed permanently, retries exhausted: eventId={}, notificationEventId={}, attemptNumber={}, error={}",
                                command.eventId(), command.notificationEventId(), command.attemptNumber(),
                                result.errorMessage());
                    } else {
                        event.scheduleRetry(retryPolicy.nextAttemptAt(command.attemptNumber(), completedAt));
                        log.info(
                                "delivery failed, retry scheduled: eventId={}, notificationEventId={}, attemptNumber={}, nextAttemptAt={}, error={}",
                                command.eventId(), command.notificationEventId(), command.attemptNumber(),
                                event.getNextAttemptAt(), result.errorMessage());
                    }
                }
                case PERMANENT_FAILURE -> {
                    attempt.markFailed(result.errorMessage(), result.httpStatus(), completedAt);
                    event.markFailed();
                    log.warn(
                            "notification failed permanently: eventId={}, notificationEventId={}, attemptNumber={}, error={}",
                            command.eventId(), command.notificationEventId(), command.attemptNumber(),
                            result.errorMessage());
                }
            }
            deliveryAttemptRepository.save(attempt);
            notificationEventRepository.save(event);
        } catch (OptimisticLockingFailureException | InvalidStatusTransitionException e) {
            // A concurrent transition (e.g. a stale-delivery recovery) is
            // already authoritative; the newer state is never overwritten
            // and no duplicate delivery work is created.
            log.warn(
                    "optimistic locking conflict while recording delivery result, concurrent state is authoritative: eventId={}, notificationEventId={}, deliveryAttemptId={}, attemptNumber={}",
                    command.eventId(), command.notificationEventId(), command.deliveryAttemptId(),
                    command.attemptNumber());
        }
    }
}
