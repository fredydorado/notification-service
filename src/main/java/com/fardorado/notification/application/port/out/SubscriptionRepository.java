package com.fardorado.notification.application.port.out;

import java.util.List;
import java.util.Optional;

import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

/**
 * Output port for persisting and reading subscriptions.
 */
public interface SubscriptionRepository {

    Subscription save(Subscription subscription);

    Optional<Subscription> findById(Long id);

    List<Subscription> findByClientIdAndEventTypeAndStatus(
            String clientId,
            EventType eventType,
            SubscriptionStatus status);
}
