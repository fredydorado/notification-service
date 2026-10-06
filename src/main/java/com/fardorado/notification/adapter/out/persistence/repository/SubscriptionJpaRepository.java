package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fardorado.notification.adapter.out.persistence.entity.SubscriptionEntity;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, Long> {

    List<SubscriptionEntity> findByClientIdAndEventTypeAndStatus(
            String clientId,
            EventType eventType,
            SubscriptionStatus status);
}
