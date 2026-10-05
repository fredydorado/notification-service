package com.fardorado.notification.domain.model.notification;

/**
 * The type of business event the notification service can process.
 *
 * <p>Database representation is the lower-case snake_case form of the enum
 * name (e.g. {@code CREDIT_CARD_PAYMENT} is stored as
 * {@code credit_card_payment}).</p>
 */
public enum EventType {
    CREDIT_CARD_PAYMENT,
    CASH_WITHDRAWAL,
    CREDIT_TRANSFER
}
