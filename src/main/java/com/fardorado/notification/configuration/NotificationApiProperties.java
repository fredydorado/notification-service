package com.fardorado.notification.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the REST API boundary.
 */
@ConfigurationProperties("notification.api")
public record NotificationApiProperties(
        /**
         * Page size applied when a request does not specify one.
         */
        @DefaultValue("20") int defaultPageSize,
        /**
         * Largest page size a client may request. Larger values are rejected
         * with 400 rather than silently clamped, so a client is never misled
         * about how much data it received.
         */
        @DefaultValue("100") int maxPageSize) {
}
