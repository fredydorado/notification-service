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
 */
public record WebhookDeliveryResult(DeliveryOutcome outcome, String errorMessage) {

    public enum DeliveryOutcome {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public static WebhookDeliveryResult success() {
        return new WebhookDeliveryResult(DeliveryOutcome.SUCCESS, null);
    }

    public static WebhookDeliveryResult retryableFailure(String errorMessage) {
        return new WebhookDeliveryResult(DeliveryOutcome.RETRYABLE_FAILURE, errorMessage);
    }

    public static WebhookDeliveryResult permanentFailure(String errorMessage) {
        return new WebhookDeliveryResult(DeliveryOutcome.PERMANENT_FAILURE, errorMessage);
    }

    public boolean successful() {
        return outcome == DeliveryOutcome.SUCCESS;
    }
}
