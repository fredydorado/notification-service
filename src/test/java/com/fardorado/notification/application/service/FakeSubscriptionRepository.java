package com.fardorado.notification.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

/**
 * In-memory fake of {@link SubscriptionRepository} for application layer
 * unit tests.
 */
class FakeSubscriptionRepository implements SubscriptionRepository {

    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    Subscription addSubscription(String clientId, EventType eventType, SubscriptionStatus status) {
        Subscription subscription = new Subscription(
                idSequence.incrementAndGet(), clientId, eventType, status,
                "https://client.example.com/webhook", 0L);
        subscriptions.put(subscription.getId(), subscription);
        return subscription;
    }

    @Override
    public Subscription save(Subscription subscription) {
        subscriptions.put(subscription.getId(), subscription);
        return subscription;
    }

    @Override
    public Optional<Subscription> findById(Long id) {
        return Optional.ofNullable(subscriptions.get(id));
    }

    @Override
    public List<Subscription> findByClientIdAndEventTypeAndStatus(
            String clientId,
            EventType eventType,
            SubscriptionStatus status) {
        return subscriptions.values().stream()
                .filter(subscription -> subscription.getClientId().equals(clientId))
                .filter(subscription -> subscription.getEventType() == eventType)
                .filter(subscription -> subscription.getStatus() == status)
                .toList();
    }

    List<Subscription> all() {
        return new ArrayList<>(subscriptions.values());
    }
}
