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
import com.fardorado.notification.application.result.NotificationEventSummaryResult;
import com.fardorado.notification.application.result.PagedResult;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * In-memory fake of {@link NotificationEventRepository} for application
 * layer unit tests.
 *
 * <p>{@link #claimDeliverable} returns the events staged via
 * {@link #stageClaimable} (claim filtering is the persistence adapter's
 * concern and is covered by its integration tests). For the same reason
 * {@link #findSummariesByClientId} applies ownership but not date/status
 * filtering or attempt enrichment; it records the criteria it was called
 * with so that use-case tests can assert what was delegated.</p>
 */
class FakeNotificationEventRepository implements NotificationEventRepository {

    private final Map<Long, NotificationEvent> eventsById = new HashMap<>();
    private final Map<String, NotificationEvent> eventsByEventId = new HashMap<>();
    private final Map<String, String> clientIdByEventId = new HashMap<>();
    private final List<NotificationEvent> claimable = new ArrayList<>();
    private final AtomicLong idSequence = new AtomicLong();
    private final List<String> savedEventIds = new ArrayList<>();

    /**
     * The arguments of the most recent {@link #findSummariesByClientId} call.
     */
    ListCriteria lastListCriteria;

    record ListCriteria(
            String clientId,
            Instant from,
            Instant to,
            NotificationEventStatus status,
            int page,
            int size) {
    }

    /**
     * Declares which client owns an event, standing in for the
     * notification_event -> subscription -> client_id join.
     */
    void stageOwnership(String eventId, String clientId) {
        clientIdByEventId.put(eventId, clientId);
    }

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
                    saved.getCreatedAt(),
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
                    saved.getCreatedAt(),
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
    public Optional<NotificationEvent> findByEventIdAndClientId(String eventId, String clientId) {
        return Optional.ofNullable(eventsByEventId.get(eventId))
                .filter(event -> clientId.equals(clientIdByEventId.get(eventId)));
    }

    @Override
    public PagedResult<NotificationEventSummaryResult> findSummariesByClientId(
            String clientId,
            Instant from,
            Instant to,
            NotificationEventStatus status,
            int page,
            int size) {
        lastListCriteria = new ListCriteria(clientId, from, to, status, page, size);

        List<NotificationEventSummaryResult> items = eventsByEventId.values().stream()
                .filter(event -> clientId.equals(clientIdByEventId.get(event.getEventId())))
                .map(event -> new NotificationEventSummaryResult(
                        event.getEventId(),
                        event.getEventType(),
                        event.getCreatedAt(),
                        event.getStatus(),
                        0,
                        null))
                .toList();

        return PagedResult.of(items, page, size, items.size());
    }

    @Override
    public List<NotificationEvent> claimDeliverable(int limit, Instant now, Instant staleThreshold) {
        return claimable.stream().limit(limit).toList();
    }

    List<String> savedEventIds() {
        return savedEventIds;
    }
}
