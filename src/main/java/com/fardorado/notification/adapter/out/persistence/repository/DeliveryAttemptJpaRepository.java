package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fardorado.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;
import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;

public interface DeliveryAttemptJpaRepository extends JpaRepository<DeliveryAttemptEntity, Long> {

    List<DeliveryAttemptEntity> findByNotificationEventIdOrderByAttemptNumberAsc(Long notificationEventId);

    Optional<DeliveryAttemptEntity> findFirstByNotificationEventIdOrderByAttemptNumberDesc(
            Long notificationEventId);

    List<DeliveryAttemptEntity> findByNotificationEventIdAndStatus(
            Long notificationEventId,
            DeliveryAttemptStatus status);

    /**
     * Loads the attempts of several notification events at once, so that a
     * page of event summaries can be enriched with its attempt counts and
     * last HTTP statuses without a query per event.
     */
    List<DeliveryAttemptEntity> findByNotificationEventIdInOrderByAttemptNumberAsc(
            Collection<Long> notificationEventIds);
}
