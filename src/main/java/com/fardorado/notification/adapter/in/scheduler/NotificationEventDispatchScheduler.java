package com.fardorado.notification.adapter.in.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fardorado.notification.application.port.in.DispatchDeliveriesUseCase;

/**
 * The retry/dispatch scheduler driving adapter: periodically locates
 * notification events that are due for delivery work (pending first
 * deliveries, due retries and stale deliveries to recover) and hands them to
 * the dispatch use case, which claims them safely and submits them to the
 * delivery worker pool.
 */
@Component
@ConditionalOnProperty(name = "notification.processing.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationEventDispatchScheduler {

    private final DispatchDeliveriesUseCase dispatchDeliveriesUseCase;

    public NotificationEventDispatchScheduler(DispatchDeliveriesUseCase dispatchDeliveriesUseCase) {
        this.dispatchDeliveriesUseCase = dispatchDeliveriesUseCase;
    }

    @Scheduled(fixedDelayString = "${notification.processing.poll-interval:5s}")
    public void dispatchDueDeliveries() {
        dispatchDeliveriesUseCase.dispatchDueDeliveries();
    }
}
