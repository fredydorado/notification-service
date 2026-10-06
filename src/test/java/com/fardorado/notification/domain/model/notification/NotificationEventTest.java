package com.fardorado.notification.domain.model.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;

class NotificationEventTest {

    private static final Instant NEXT_ATTEMPT = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void newEventStartsPending() {
        NotificationEvent event = newEvent(NotificationEventStatus.PENDING);

        assertThat(event.getEventId()).isEqualTo("evt-1");
        assertThat(event.getEventVersion()).isEqualTo(2);
        assertThat(event.getCorrelationId()).isEqualTo("corr-1");
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PENDING);
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldTransitionPendingToDelivering() {
        NotificationEvent event = newEvent(NotificationEventStatus.PENDING);

        event.markDelivering();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.DELIVERING);
    }

    @Test
    void shouldTransitionDeliveringToCompleted() {
        NotificationEvent event = newEvent(NotificationEventStatus.DELIVERING);

        event.markCompleted();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.COMPLETED);
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldTransitionDeliveringToRetryScheduled() {
        NotificationEvent event = newEvent(NotificationEventStatus.DELIVERING);

        event.scheduleRetry(NEXT_ATTEMPT);

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.RETRY_SCHEDULED);
        assertThat(event.getNextAttemptAt()).isEqualTo(NEXT_ATTEMPT);
    }

    @Test
    void shouldTransitionDeliveringToFailed() {
        NotificationEvent event = newEvent(NotificationEventStatus.DELIVERING);

        event.markFailed();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.FAILED);
        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void shouldTransitionRetryScheduledToDelivering() {
        NotificationEvent event = newEvent(NotificationEventStatus.RETRY_SCHEDULED);
        event.getNextAttemptAt();

        event.markDelivering();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.DELIVERING);
    }

    @Test
    void shouldTransitionFailedToPendingOnReplay() {
        NotificationEvent event = newEvent(NotificationEventStatus.FAILED);

        event.replay();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PENDING);
    }

    @Test
    void shouldTransitionPendingToFailedWhenUnmatched() {
        NotificationEvent event = newEvent(NotificationEventStatus.PENDING);

        event.markUnmatched();

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.FAILED);
    }

    @Test
    void markDeliveringClearsNextAttemptAt() {
        NotificationEvent event = newEvent(NotificationEventStatus.DELIVERING);
        event.scheduleRetry(NEXT_ATTEMPT);

        event.markDelivering();

        assertThat(event.getNextAttemptAt()).isNull();
    }

    @Test
    void scheduleRetryRequiresNextAttemptAt() {
        NotificationEvent event = newEvent(NotificationEventStatus.DELIVERING);

        assertThatThrownBy(() -> event.scheduleRetry(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidTransitions() {
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.PENDING).markCompleted())
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.PENDING).markFailed())
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.COMPLETED).markDelivering())
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.COMPLETED).markFailed())
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.FAILED).markDelivering())
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> {
            NotificationEvent event = newEvent(NotificationEventStatus.DELIVERING);
            event.scheduleRetry(NEXT_ATTEMPT);
            event.replay();
        }).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.FAILED).markUnmatched())
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldRejectInvalidEventVersion() {
        assertThatThrownBy(() -> newEvent(NotificationEventStatus.PENDING, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
    }

    private NotificationEvent newEvent(NotificationEventStatus status) {
        return newEvent(status, 2);
    }

    private NotificationEvent newEvent(NotificationEventStatus status, int eventVersion) {
        return new NotificationEvent(
                1L, "evt-1", EventType.CREDIT_CARD_PAYMENT, eventVersion, "corr-1",
                status, "{}", null, null, 0L);
    }
}
