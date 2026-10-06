package com.fardorado.notification.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.domain.model.notification.NotificationEvent;

/**
 * In-memory fake of {@link NotificationEventRepository} for application
 * layer unit tests.
 *
 * <p>{@link #claimDeliverable} returns the events staged via
 * {@link #stageClaimable} (claim filtering is the persistence adapter's
 * concern and is covered by its integration tests).</p>
 */
class FakeNotificationEventRepository implements NotificationEventRepository {

    private final Map<Long, NotificationEvent> eventsById = new HashMap<>();
    private final Map<String, NotificationEvent> eventsByEventId = new HashMap<>();
    private final List<NotificationEvent> claimable = new ArrayList<>();
    private final AtomicLong idSequence = new AtomicLong();
    private final List<String> savedEventIds = new ArrayList<>();

    /**
     * Optional hook invoked on save; used to simulate persistence failures or
     * optimistic-locking conflicts.
     */
    Function<NotificationEvent, NotificationEvent> onSave = Function.identity();

    void stageClaimable(NotificationEvent event) {
        claimable.add(event);
    }

    @Override
    public NotificationEvent save(NotificationEvent notificationEvent) {
        NotificationEvent saved = onSave.apply(notificationEvent);
        if (saved.getId() == null) {
            saved = new NotificationEvent(
                    idSequence.incrementAndGet(),
                    saved.getEventId(),
                    saved.getEventType(),
                    saved.getEventVersion(),
                    saved.getCorrelationId(),
                    saved.getStatus(),
                    saved.getPayload(),
                    saved.getSubscriptionId(),
                    saved.getNextAttemptAt(),
                    0L);
        } else {
            saved = new NotificationEvent(
                    saved.getId(),
                    saved.getEventId(),
                    saved.getEventType(),
                    saved.getEventVersion(),
                    saved.getCorrelationId(),
                    saved.getStatus(),
                    saved.getPayload(),
                    saved.getSubscriptionId(),
                    saved.getNextAttemptAt(),
                    (saved.getVersion() == null ? 0L : saved.getVersion()) + 1);
        }
        eventsById.put(saved.getId(), saved);
        eventsByEventId.put(saved.getEventId(), saved);
        savedEventIds.add(saved.getEventId());
        return saved;
    }

    @Override
    public Optional<NotificationEvent> findById(Long id) {
        return Optional.ofNullable(eventsById.get(id));
    }

    @Override
    public Optional<NotificationEvent> findByEventId(String eventId) {
        return Optional.ofNullable(eventsByEventId.get(eventId));
    }

    @Override
    public List<NotificationEvent> claimDeliverable(int limit, Instant now, Instant staleThreshold) {
        return claimable.stream().limit(limit).toList();
    }

    List<String> savedEventIds() {
        return savedEventIds;
    }
}
