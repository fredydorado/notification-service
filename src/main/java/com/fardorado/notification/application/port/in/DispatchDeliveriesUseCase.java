package com.fardorado.notification.application.port.in;

/**
 * Input port for dispatching the notification events that are due for
 * delivery work.
 *
 * <p>Claimed events are transitioned to {@code DELIVERING} (with a delivery
 * attempt created) and submitted to the delivery worker pool. Stale
 * {@code DELIVERING} events are recovered according to the retry policy.</p>
 */
public interface DispatchDeliveriesUseCase {

    void dispatchDueDeliveries();
}
