package com.fardorado.notification.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.application.command.ProcessNotificationEventCommand;
import com.fardorado.notification.application.port.in.ProcessNotificationEventUseCase;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.subscription.Subscription;

/**
 * Ingests a source event received from the event broker.
 *
 * <p>Idempotency is enforced durably: an event whose {@code eventId} was
 * already persisted is recognized and skipped. New events are persisted
 * together with the subscription matched for {@code (clientId, eventType)};
 * unmatched events are persisted directly as {@code FAILED} because there is
 * nothing to deliver. The broker message may only be acknowledged after this
 * method returns successfully, so the transaction boundary deliberately covers
 * only persistence (no external calls happen here).</p>
 */
@Component
public class ProcessNotificationEventUseCaseImpl implements ProcessNotificationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessNotificationEventUseCaseImpl.class);

    private final NotificationEventRepository notificationEventRepository;
    private final SubscriptionMatcher subscriptionMatcher;

    public ProcessNotificationEventUseCaseImpl(
            NotificationEventRepository notificationEventRepository,
            SubscriptionMatcher subscriptionMatcher) {
        this.notificationEventRepository = notificationEventRepository;
        this.subscriptionMatcher = subscriptionMatcher;
    }

    @Transactional
    @Override
    public void processNotificationEvent(ProcessNotificationEventCommand command) {
        if (notificationEventRepository.findByEventId(command.eventId()).isPresent()) {
            log.info(
                    "duplicate event detected: eventId={}, eventType={}, clientId={} (already persisted, skipping)",
                    command.eventId(), command.eventType(), command.clientId());
            return;
        }

        Subscription matched = subscriptionMatcher
                .match(command.clientId(), command.eventType())
                .orElse(null);

        NotificationEvent event;
        if (matched != null) {
            event = NotificationEvent.newEvent(
                    command.eventId(),
                    command.eventType(),
                    command.eventVersion(),
                    command.correlationId(),
                    command.content(),
                    matched.getId());
            log.info(
                    "subscription matched: eventId={}, clientId={}, eventType={}, subscriptionId={}",
                    command.eventId(), command.clientId(), command.eventType(), matched.getId());
        } else {
            event = NotificationEvent.newEvent(
                    command.eventId(),
                    command.eventType(),
                    command.eventVersion(),
                    command.correlationId(),
                    command.content(),
                    null);
            event.markUnmatched();
            log.warn(
                    "no active subscription matched: eventId={}, clientId={}, eventType={} (event failed)",
                    command.eventId(), command.clientId(), command.eventType());
        }

        NotificationEvent saved = notificationEventRepository.save(event);
        log.info(
                "event persisted: eventId={}, notificationEventId={}, status={}, subscriptionId={}",
                saved.getEventId(), saved.getId(), saved.getStatus(), saved.getSubscriptionId());
    }
}
