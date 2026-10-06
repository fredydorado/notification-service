package com.fardorado.notification.domain.model.subscription;

import java.util.Objects;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;
import com.fardorado.notification.domain.model.notification.EventType;

/**
 * A customer's subscription to a particular notification event type.
 *
 * <p>The only delivery channel is WEBHOOK, so the channel is not modeled
 * explicitly: the {@code webhookUrl} is the delivery target.</p>
 */
public class Subscription {

    private final Long id;
    private final String clientId;
    private final EventType eventType;
    private final String webhookUrl;
    /**
     * Opaque concurrency token managed by the persistence layer. The domain
     * never modifies it; it is carried so that stale updates can be detected
     * at the persistence boundary.
     */
    private final Long version;
    private SubscriptionStatus status;

    /**
     * Creates a new, not yet persisted subscription. Its initial status is
     * {@link SubscriptionStatus#ACTIVE}.
     */
    public static Subscription newSubscription(
            String clientId,
            EventType eventType,
            String webhookUrl) {
        return new Subscription(null, clientId, eventType, SubscriptionStatus.ACTIVE, webhookUrl, null);
    }

    /**
     * Reconstitutes a subscription from persisted state.
     */
    public Subscription(
            Long id,
            String clientId,
            EventType eventType,
            SubscriptionStatus status,
            String webhookUrl,
            Long version) {
        this.id = id;
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl must not be null");
        this.version = version;
    }

    /**
     * Activates a currently inactive subscription.
     */
    public void activate() {
        transitionTo(SubscriptionStatus.ACTIVE, SubscriptionStatus.INACTIVE);
    }

    /**
     * Deactivates a currently active subscription.
     */
    public void deactivate() {
        transitionTo(SubscriptionStatus.INACTIVE, SubscriptionStatus.ACTIVE);
    }

    private void transitionTo(SubscriptionStatus target, SubscriptionStatus... allowedSources) {
        for (SubscriptionStatus source : allowedSources) {
            if (this.status == source) {
                this.status = target;
                return;
            }
        }
        throw new InvalidStatusTransitionException(
                "Cannot transition subscription from %s to %s".formatted(this.status, target));
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
