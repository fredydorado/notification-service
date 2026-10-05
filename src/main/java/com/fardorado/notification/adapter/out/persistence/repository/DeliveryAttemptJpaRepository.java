package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fardorado.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;

public interface DeliveryAttemptJpaRepository extends JpaRepository<DeliveryAttemptEntity, Long> {

    List<DeliveryAttemptEntity> findByNotificationEventIdOrderByAttemptNumberAsc(Long notificationEventId);
}
