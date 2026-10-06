package com.fardorado.notification.application.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;

/**
 * In-memory fake of {@link DeliveryAttemptRepository} for application layer
 * unit tests.
 */
class FakeDeliveryAttemptRepository implements DeliveryAttemptRepository {

    private final Map<Long, DeliveryAttempt> attemptsById = new HashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    @Override
    public DeliveryAttempt save(DeliveryAttempt deliveryAttempt) {
        DeliveryAttempt saved = deliveryAttempt;
        if (saved.getId() == null) {
            saved = new DeliveryAttempt(
                    idSequence.incrementAndGet(),
                    saved.getNotificationEventId(),
                    saved.getStatus(),
                    saved.getAttemptNumber(),
                    saved.getErrorMessage(),
                    saved.getCompletedAt(),
                    0L);
        } else {
            saved = new DeliveryAttempt(
                    saved.getId(),
                    saved.getNotificationEventId(),
                    saved.getStatus(),
                    saved.getAttemptNumber(),
                    saved.getErrorMessage(),
                    saved.getCompletedAt(),
                    (saved.getVersion() == null ? 0L : saved.getVersion()) + 1);
        }
        attemptsById.put(saved.getId(), saved);
        return saved;
    }

    @Override
    public Optional<DeliveryAttempt> findById(Long id) {
        return Optional.ofNullable(attemptsById.get(id));
    }

    @Override
    public List<DeliveryAttempt> findByNotificationEventIdOrderByAttemptNumber(Long notificationEventId) {
        return attemptsById.values().stream()
                .filter(attempt -> attempt.getNotificationEventId().equals(notificationEventId))
                .sorted(java.util.Comparator.comparingInt(DeliveryAttempt::getAttemptNumber))
                .toList();
    }

    @Override
    public int nextAttemptNumber(Long notificationEventId) {
        return findByNotificationEventIdOrderByAttemptNumber(notificationEventId).stream()
                .mapToInt(DeliveryAttempt::getAttemptNumber)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public List<DeliveryAttempt> findByNotificationEventIdAndStatus(
            Long notificationEventId, DeliveryAttemptStatus status) {
        return attemptsById.values().stream()
                .filter(attempt -> attempt.getNotificationEventId().equals(notificationEventId))
                .filter(attempt -> attempt.getStatus() == status)
                .toList();
    }
}
