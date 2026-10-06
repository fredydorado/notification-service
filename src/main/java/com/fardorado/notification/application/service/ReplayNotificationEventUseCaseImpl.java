package com.fardorado.notification.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.application.command.ReplayNotificationEventCommand;
import com.fardorado.notification.application.exception.NotificationEventNotReplayableException;
import com.fardorado.notification.application.exception.ResourceConflictException;
import com.fardorado.notification.application.exception.ResourceNotFoundException;
import com.fardorado.notification.application.port.in.ReplayNotificationEventUseCase;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.result.ReplayNotificationEventResult;
import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * Requeues a definitively failed notification event for processing.
 *
 * <p>The use case performs only the {@code FAILED -> PENDING} transition and
 * returns. The existing dispatch scheduler claims the event on its next
 * sweep, so replay is asynchronous and no delivery happens on this thread.</p>
 *
 * <p>Concurrency is handled by the existing optimistic-locking mechanism: two
 * simultaneous replays both read the same {@code FAILED} event, but only one
 * save succeeds. The loser's stale write is rejected and surfaces as a
 * conflict, so a replay is never processed twice.</p>
 */
@Component
public class ReplayNotificationEventUseCaseImpl implements ReplayNotificationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReplayNotificationEventUseCaseImpl.class);

    private final NotificationEventRepository notificationEventRepository;

    public ReplayNotificationEventUseCaseImpl(NotificationEventRepository notificationEventRepository) {
        this.notificationEventRepository = notificationEventRepository;
    }

    @Override
    @Transactional
    public ReplayNotificationEventResult replayNotificationEvent(ReplayNotificationEventCommand command) {
        NotificationEvent event = notificationEventRepository
                .findByEventIdAndClientId(command.eventId(), command.clientId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification event %s not found".formatted(command.eventId())));

        NotificationEventStatus statusBeforeReplay = event.getStatus();
        try {
            event.replay();
        } catch (InvalidStatusTransitionException e) {
            throw new NotificationEventNotReplayableException(command.eventId(), statusBeforeReplay);
        }

        NotificationEvent replayed;
        try {
            replayed = notificationEventRepository.save(event);
        } catch (OptimisticLockingFailureException e) {
            // Another request already replayed this event; the transition
            // must not be applied a second time.
            throw new ResourceConflictException(
                    "Notification event %s is already being replayed".formatted(command.eventId()));
        }

        log.info("notification event replay accepted: eventId={}, notificationEventId={}, previousStatus={}",
                command.eventId(), replayed.getId(), statusBeforeReplay);

        return new ReplayNotificationEventResult(replayed.getEventId(), replayed.getStatus());
    }
}
