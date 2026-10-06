package com.fardorado.notification.adapter.out.webhook;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.fardorado.notification.application.port.out.NotificationChannelClient;
import com.fardorado.notification.application.port.out.WebhookDeliveryCommand;
import com.fardorado.notification.application.port.out.WebhookDeliveryResult;

/**
 * The WEBHOOK notification channel adapter: performs the external HTTP POST
 * to the subscription's webhook URL and converts the transport outcome into
 * a {@link WebhookDeliveryResult} classified for the retry policy.
 *
 * <p>This adapter never changes notification-event or delivery-attempt
 * state; that is the application layer's job (see
 * {@code DeliveryResultService}).</p>
 */
@Component
public class WebhookChannelClientImpl implements NotificationChannelClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public WebhookChannelClientImpl(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public WebhookDeliveryResult deliver(WebhookDeliveryCommand command) {
        try {
            String body = buildRequestBody(command);
            RestClient.RequestBodySpec request = restClient
                    .post()
                    .uri(URI.create(command.webhookUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
            if (command.correlationId() != null) {
                request = request.header("X-Correlation-Id", command.correlationId());
            }
            return request.exchange((req, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (status.is2xxSuccessful()) {
                    return WebhookDeliveryResult.success();
                }
                String reason = "webhook endpoint returned HTTP %d".formatted(status.value());
                return isRetryable(status)
                        ? WebhookDeliveryResult.retryableFailure(reason)
                        : WebhookDeliveryResult.permanentFailure(reason);
            });
        } catch (RuntimeException e) {
            // Timeouts and connection failures (e.g. the endpoint is briefly
            // unavailable) are retryable; the retry policy bounds the total
            // number of attempts.
            return WebhookDeliveryResult.retryableFailure(
                    "webhook call failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 429 (Too Many Requests) and 5xx responses are retryable; other 4xx
     * responses are permanent (the endpoint rejected the notification).
     */
    private boolean isRetryable(HttpStatusCode status) {
        int value = status.value();
        return value == 429 || value >= 500;
    }

    private String buildRequestBody(WebhookDeliveryCommand command) {
        try {
            var root = jsonMapper.createObjectNode();
            root.put("event_id", command.eventId());
            root.put("event_type", command.eventType().name().toLowerCase(Locale.ROOT));
            root.put("event_version", command.eventVersion());
            if (command.correlationId() != null) {
                root.put("correlation_id", command.correlationId());
            }
            root.set("content", jsonMapper.readTree(command.content()));
            return root.toString();
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot serialize webhook request body", e);
        }
    }
}
