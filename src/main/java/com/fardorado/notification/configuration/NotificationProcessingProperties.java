package com.fardorado.notification.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the notification event processing pipeline.
 */
@ConfigurationProperties("notification.processing")
public record NotificationProcessingProperties(
        /**
         * Maximum number of delivery attempts (including the first one)
         * before a notification event fails permanently.
         */
        @DefaultValue("5") int maxAttempts,
        /**
         * Backoff before the first retry; doubles for each subsequent retry.
         */
        @DefaultValue("30s") Duration backoffInitial,
        /**
         * Upper bound for the retry backoff.
         */
        @DefaultValue("10m") Duration backoffMax,
        /**
         * Delivery lease period: a {@code DELIVERING} event whose last update
         * is older than this is considered stale (e.g. after a crash) and is
         * recovered according to the retry policy.
         */
        @DefaultValue("5m") Duration leaseTimeout,
        /**
         * Maximum number of events claimed and dispatched per poll.
         */
        @DefaultValue("50") int claimBatchSize,
        /**
         * Maximum number of concurrent external deliveries.
         */
        @DefaultValue("10") int workerPoolSize) {
}
