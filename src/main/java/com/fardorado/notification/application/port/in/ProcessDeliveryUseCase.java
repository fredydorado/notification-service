package com.fardorado.notification.application.port.in;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;

/**
 * Input port for executing one delivery attempt of a notification event.
 *
 * <p>Invokes the external delivery channel and records the result. The
 * external channel call runs outside any database transaction; only the
 * claim and result-recording steps are transactional.</p>
 */
public interface ProcessDeliveryUseCase {

    void processDelivery(ProcessDeliveryCommand command);
}
