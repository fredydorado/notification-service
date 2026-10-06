package com.fardorado.notification.application.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;
import com.fardorado.notification.application.port.in.DispatchDeliveriesUseCase;
import com.fardorado.notification.application.port.in.ProcessDeliveryUseCase;
import com.fardorado.notification.configuration.NotificationProcessingProperties;

/**
 * Dispatches the notification events that are due for delivery work to the
 * bounded delivery worker pool.
 *
 * <p>Claiming and preparation (state transition + delivery-attempt creation)
 * run in one short transaction inside {@link DeliveryClaimService}; the actual
 * webhook deliveries run on worker threads so slow external endpoints never
 * block the dispatcher thread pool.</p>
 */
@Component
public class DispatchDeliveriesUseCaseImpl implements DispatchDeliveriesUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchDeliveriesUseCaseImpl.class);

    private final DeliveryClaimService deliveryClaimService;
    private final ProcessDeliveryUseCase processDeliveryUseCase;
    private final AsyncTaskExecutor deliveryWorkerExecutor;
    private final NotificationProcessingProperties properties;

    public DispatchDeliveriesUseCaseImpl(
            DeliveryClaimService deliveryClaimService,
            ProcessDeliveryUseCase processDeliveryUseCase,
            @Qualifier("deliveryWorkerExecutor") AsyncTaskExecutor deliveryWorkerExecutor,
            NotificationProcessingProperties properties) {
        this.deliveryClaimService = deliveryClaimService;
        this.processDeliveryUseCase = processDeliveryUseCase;
        this.deliveryWorkerExecutor = deliveryWorkerExecutor;
        this.properties = properties;
    }

    @Override
    public void dispatchDueDeliveries() {
        List<ProcessDeliveryCommand> commands =
                deliveryClaimService.claimDeliveries(properties.claimBatchSize());
        for (ProcessDeliveryCommand command : commands) {
            // The claim transaction has committed at this point, so the event
            // is already DELIVERING with an IN_PROGRESS attempt; if the app
            // crashes before the task runs, the stale-delivery recovery
            // handles it.
            deliveryWorkerExecutor.submit(() -> processDeliveryUseCase.processDelivery(command));
        }
        if (!commands.isEmpty()) {
            log.info("retry dispatched: claimedDeliveries={}", commands.size());
        }
    }
}
