package com.fardorado.notification.adapter.out.persistence.repository;

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
}
