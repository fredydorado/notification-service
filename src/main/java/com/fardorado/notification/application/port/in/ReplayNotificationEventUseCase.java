package com.fardorado.notification.application.port.in;

import com.fardorado.notification.application.command.ReplayNotificationEventCommand;
import com.fardorado.notification.application.result.ReplayNotificationEventResult;

/**
 * Requests reprocessing of a definitively failed notification event.
 *
 * <p>The operation only performs the {@code FAILED -> PENDING} transition and
 * returns; the existing dispatch and retry infrastructure picks the event up
 * on its next sweep. It never waits for webhook delivery.</p>
 */
public interface ReplayNotificationEventUseCase {

    ReplayNotificationEventResult replayNotificationEvent(ReplayNotificationEventCommand command);
}
