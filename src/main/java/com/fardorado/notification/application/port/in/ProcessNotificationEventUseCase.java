package com.fardorado.notification.application.port.in;

import com.fardorado.notification.application.command.ProcessNotificationEventCommand;

/**
 * Input port for ingesting a source event received from the event broker.
 *
 * <p>The event is durably persisted (idempotently, by {@code eventId}) and
 * matched against the active subscription for {@code (clientId, eventType)}.
 * The broker message may only be acknowledged after this use case has
 * completed successfully.</p>
 */
public interface ProcessNotificationEventUseCase {

    void processNotificationEvent(ProcessNotificationEventCommand command);
}
