package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.configuration.NotificationProcessingProperties;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final RetryPolicy retryPolicy = new RetryPolicy(new NotificationProcessingProperties(
            5, Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(5), 50, 10));

    @Test
    void isExhaustedOnlyAtOrBeyondMaxAttempts() {
        assertThat(retryPolicy.isExhausted(1)).isFalse();
        assertThat(retryPolicy.isExhausted(4)).isFalse();
        assertThat(retryPolicy.isExhausted(5)).isTrue();
        assertThat(retryPolicy.isExhausted(6)).isTrue();
    }

    @Test
    void nextAttemptAtDoublesInitialBackoffPerAttempt() {
        assertThat(retryPolicy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(30));
        assertThat(retryPolicy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(60));
        assertThat(retryPolicy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(120));
        assertThat(retryPolicy.nextAttemptAt(4, NOW)).isEqualTo(NOW.plusSeconds(240));
    }

    @Test
    void nextAttemptAtCapsBackoffAtMaximum() {
        assertThat(retryPolicy.nextAttemptAt(5, NOW)).isEqualTo(NOW.plusSeconds(480));
        assertThat(retryPolicy.nextAttemptAt(6, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(retryPolicy.nextAttemptAt(100, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void nextAttemptAtTreatsMissingAttemptNumberAsFirst() {
        assertThat(retryPolicy.nextAttemptAt(0, NOW)).isEqualTo(NOW.plusSeconds(30));
    }
}
