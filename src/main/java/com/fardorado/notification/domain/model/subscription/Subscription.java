package com.fardorado.notification.domain.model.subscription;

import java.util.Objects;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationChannel;

/**
 * A customer's subscription to a particular event type through a
 * notification channel.
 */
public class Subscription {

    private final Long id;
    private final String subscriberId;
    private final EventType eventType;
    private final NotificationChannel channel;
    private final String endpoint;
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
            String subscriberId,
            EventType eventType,
            NotificationChannel channel,
            String endpoint) {
        return new Subscription(null, subscriberId, eventType, channel, SubscriptionStatus.ACTIVE, endpoint, null);
    }

    /**
     * Reconstitutes a subscription from persisted state.
     */
    public Subscription(
            Long id,
            String subscriberId,
            EventType eventType,
            NotificationChannel channel,
            SubscriptionStatus status,
            String endpoint,
            Long version) {
        this.id = id;
        this.subscriberId = Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
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

    public String getSubscriberId() {
        return subscriberId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Long getVersion() {
        return version;
    }
}
