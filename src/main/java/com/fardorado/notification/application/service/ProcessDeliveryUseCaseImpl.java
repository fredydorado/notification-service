package com.fardorado.notification.application.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.fardorado.notification.application.command.ProcessDeliveryCommand;
import com.fardorado.notification.application.port.in.ProcessDeliveryUseCase;
import com.fardorado.notification.application.port.out.NotificationChannelClient;
import com.fardorado.notification.application.port.out.WebhookDeliveryCommand;
import com.fardorado.notification.application.port.out.WebhookDeliveryResult;

/**
 * Executes one delivery attempt of a notification event.
 *
 * <p>The external channel call runs outside any database transaction; only
 * the result recording (see {@link DeliveryResultService}) is transactional.
 * Unexpected worker exceptions are classified as retryable failures so the
 * event and its delivery attempt are never left permanently stuck in an
 * inconsistent state.</p>
 */
@Component
@RequiredArgsConstructor
public class ProcessDeliveryUseCaseImpl implements ProcessDeliveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessDeliveryUseCaseImpl.class);

    private final NotificationChannelClient notificationChannelClient;
    private final DeliveryResultService deliveryResultService;

    @Override
    public void processDelivery(ProcessDeliveryCommand command) {
        try {
            MDC.put("eventId", command.eventId());
            MDC.put("correlationId", command.correlationId());
            MDC.put("attemptNumber", Integer.toString(command.attemptNumber()));
            WebhookDeliveryResult result = notificationChannelClient.deliver(toWebhookCommand(command));
            deliveryResultService.recordDeliveryResult(command, result);
        } catch (RuntimeException e) {
            log.error(
                    "unexpected delivery failure, classifying as retryable: eventId={}, notificationEventId={}, deliveryAttemptId={}, attemptNumber={}",
                    command.eventId(), command.notificationEventId(), command.deliveryAttemptId(),
                    command.attemptNumber(), e);
            deliveryResultService.recordDeliveryResult(
                    command,
                    WebhookDeliveryResult.retryableFailure(
                            "unexpected delivery failure: " + e.getClass().getSimpleName(), null));
        } finally {
            MDC.remove("eventId");
            MDC.remove("correlationId");
            MDC.remove("attemptNumber");
        }
    }

    private WebhookDeliveryCommand toWebhookCommand(ProcessDeliveryCommand command) {
        return new WebhookDeliveryCommand(
                command.webhookUrl(),
                command.eventId(),
                command.eventType(),
                command.eventVersion(),
                command.correlationId(),
                command.payload());
    }
}
