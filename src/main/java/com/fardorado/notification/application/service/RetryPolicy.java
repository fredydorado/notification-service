package com.fardorado.notification.application.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fardorado.notification.configuration.NotificationProcessingProperties;

/**
 * The retry policy for failed notification deliveries.
 *
 * <p>Centralizes all retry decisions: whether a failure is covered by the
 * attempt budget, and when the next retry may run. The policy is
 * configuration-backed and free of transport details, so it stays
 * replaceable and testable.</p>
 */
@Component
public class RetryPolicy {

    private final NotificationProcessingProperties properties;

    public RetryPolicy(NotificationProcessingProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns {@code true} when the delivery attempt identified by the given
     * attempt number is the last one allowed by the attempt budget; after it
     * fails, retries are exhausted.
     */
    public boolean isExhausted(int attemptNumber) {
        return attemptNumber >= properties.maxAttempts();
    }

    /**
     * Returns the earliest time the next delivery attempt may run, computed
     * as an exponential backoff (initial backoff doubling per attempt,
     * capped at the configured maximum backoff).
     */
    public Instant nextAttemptAt(int attemptNumber, Instant now) {
        int effectiveAttemptNumber = Math.max(1, attemptNumber);
        Duration backoff = backoffFor(effectiveAttemptNumber);
        return now.plus(backoff);
    }

    private Duration backoffFor(int attemptNumber) {
        // Caps the shift to avoid overflowing long for large attempt numbers.
        int shifts = Math.min(attemptNumber - 1, 30);
        Duration initial = properties.backoffInitial();
        Duration doubled = initial.multipliedBy(1L << shifts);
        return doubled.compareTo(properties.backoffMax()) > 0
                ? properties.backoffMax()
                : doubled;
    }
}
