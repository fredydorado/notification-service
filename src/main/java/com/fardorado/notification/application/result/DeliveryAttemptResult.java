package com.fardorado.notification.application.result;

import com.fardorado.notification.domain.model.delivery.DeliveryAttemptStatus;

/**
 * One entry of a notification event's delivery-attempt history.
 *
 * @param attempt    the attempt number, starting at 1
 * @param httpStatus HTTP status returned by the webhook endpoint, or
 *                   {@code null} when no response was received
 */
public record DeliveryAttemptResult(
        int attempt,
        DeliveryAttemptStatus status,
        Integer httpStatus) {
}
