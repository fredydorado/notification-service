package com.fardorado.notification.application.port.out;

/**
 * The result of one external webhook notification delivery, classified so the
 * application layer can decide whether the delivery:
 *
 * <ul>
 *   <li>succeeded,</li>
 *   <li>should be retried later, or</li>
 *   <li>failed permanently (no further automatic retry).</li>
 * </ul>
 *
 * <p>{@code httpStatus} is the status code returned by the webhook endpoint,
 * or {@code null} when no response was received at all (connection failure,
 * timeout).</p>
 */
public record WebhookDeliveryResult(DeliveryOutcome outcome, String errorMessage, Integer httpStatus) {

    public enum DeliveryOutcome {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public static WebhookDeliveryResult success(Integer httpStatus) {
        return new WebhookDeliveryResult(DeliveryOutcome.SUCCESS, null, httpStatus);
    }

    public static WebhookDeliveryResult retryableFailure(String errorMessage, Integer httpStatus) {
        return new WebhookDeliveryResult(DeliveryOutcome.RETRYABLE_FAILURE, errorMessage, httpStatus);
    }

    public static WebhookDeliveryResult permanentFailure(String errorMessage, Integer httpStatus) {
        return new WebhookDeliveryResult(DeliveryOutcome.PERMANENT_FAILURE, errorMessage, httpStatus);
    }

    public boolean successful() {
        return outcome == DeliveryOutcome.SUCCESS;
    }
}
