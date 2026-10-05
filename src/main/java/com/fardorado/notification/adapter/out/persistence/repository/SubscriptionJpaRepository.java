package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fardorado.notification.adapter.out.persistence.entity.SubscriptionEntity;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationChannel;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, Long> {

    List<SubscriptionEntity> findByEventTypeAndChannelAndStatus(
            EventType eventType,
            NotificationChannel channel,
            SubscriptionStatus status);
}
