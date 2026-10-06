package com.fardorado.notification.domain.model.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.domain.exception.InvalidStatusTransitionException;

class DeliveryAttemptTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void startAttemptBeginsInProgress() {
        DeliveryAttempt attempt = DeliveryAttempt.startAttempt(42L, 1);

        assertThat(attempt.getNotificationEventId()).isEqualTo(42L);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.IN_PROGRESS);
        assertThat(attempt.getCompletedAt()).isNull();
        assertThat(attempt.getErrorMessage()).isNull();
    }

    @Test
    void shouldMarkSuccess() {
        DeliveryAttempt attempt = DeliveryAttempt.startAttempt(42L, 1);

        attempt.markSuccess(COMPLETED_AT);

        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.SUCCESS);
        assertThat(attempt.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(attempt.getErrorMessage()).isNull();
    }

    @Test
    void shouldMarkFailed() {
        DeliveryAttempt attempt = DeliveryAttempt.startAttempt(42L, 2);

        attempt.markFailed("webhook endpoint returned HTTP 500", COMPLETED_AT);

        assertThat(attempt.getStatus()).isEqualTo(DeliveryAttemptStatus.FAILED);
        assertThat(attempt.getCompletedAt()).isEqualTo(COMPLETED_AT);
        assertThat(attempt.getErrorMessage()).isEqualTo("webhook endpoint returned HTTP 500");
    }

    @Test
    void shouldRejectTransitionAfterTerminalState() {
        DeliveryAttempt successful = DeliveryAttempt.startAttempt(42L, 1);
        successful.markSuccess(COMPLETED_AT);
        assertThatThrownBy(() -> successful.markFailed("too late", COMPLETED_AT))
                .isInstanceOf(InvalidStatusTransitionException.class);

        DeliveryAttempt failed = DeliveryAttempt.startAttempt(42L, 1);
        failed.markFailed("webhook endpoint returned HTTP 500", COMPLETED_AT);
        assertThatThrownBy(() -> failed.markSuccess(COMPLETED_AT))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> failed.markFailed("again", COMPLETED_AT))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void shouldRejectInvalidAttemptNumber() {
        assertThatThrownBy(() -> DeliveryAttempt.startAttempt(42L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNumber");
        assertThatThrownBy(() -> DeliveryAttempt.startAttempt(42L, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNumber");
    }
}
