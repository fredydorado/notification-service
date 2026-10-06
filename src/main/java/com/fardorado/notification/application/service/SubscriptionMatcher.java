package com.fardorado.notification.application.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

/**
 * Finds the active subscription that matches an ingested event for a given
 * {@code (clientId, eventType)} combination.
 *
 * <p>Matching rules are kept here, isolated from both event ingestion and
 * delivery execution. The database enforces at most one subscription per
 * {@code (client_id, event_type)}; the first result is taken defensively.</p>
 */
@Component
public class SubscriptionMatcher {

    private final SubscriptionRepository subscriptionRepository;

    public SubscriptionMatcher(SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
    }

    /**
     * Returns the active subscription matching the given client and event
     * type, or empty if this client is not actively subscribed to this event
     * type.
     */
    public Optional<Subscription> match(String clientId, EventType eventType) {
        return subscriptionRepository
                .findByClientIdAndEventTypeAndStatus(clientId, eventType, SubscriptionStatus.ACTIVE)
                .stream()
                .findFirst();
    }
}
