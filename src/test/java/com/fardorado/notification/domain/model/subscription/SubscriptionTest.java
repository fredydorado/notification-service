package com.fardorado.notification.domain.model.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;
import com.fardorado.notification.domain.model.notification.EventType;

class SubscriptionTest {

    @Test
    void newSubscriptionStartsActive() {
        Subscription subscription = Subscription.newSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, "https://example.com/webhook");

        assertThat(subscription.getClientId()).isEqualTo("client-1");
        assertThat(subscription.getEventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(subscription.getWebhookUrl()).isEqualTo("https://example.com/webhook");
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void shouldDeactivateAndReactivate() {
        Subscription subscription = Subscription.newSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, "https://example.com/webhook");

        subscription.deactivate();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.INACTIVE);

        subscription.activate();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void shouldRejectRedundantTransitions() {
        Subscription active = Subscription.newSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, "https://example.com/webhook");
        assertThatThrownBy(active::activate)
                .isInstanceOf(InvalidStatusTransitionException.class);

        Subscription inactive = new Subscription(
                1L, "client-1", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.INACTIVE,
                "https://example.com/webhook", 0L);
        assertThatThrownBy(inactive::deactivate)
                .isInstanceOf(InvalidStatusTransitionException.class);
    }
}
