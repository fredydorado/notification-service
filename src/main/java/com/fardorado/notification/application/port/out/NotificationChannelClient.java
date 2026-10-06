package com.fardorado.notification.application.port.out;

/**
 * Output port for delivering a notification through the external delivery
 * channel (currently WEBHOOK).
 *
 * <p>Implementations perform the actual external call and convert the
 * transport-level outcome into a {@link WebhookDeliveryResult}. They must
 * never change notification-event or delivery-attempt state themselves.</p>
 */
public interface NotificationChannelClient {

    WebhookDeliveryResult deliver(WebhookDeliveryCommand command);
}
