package com.fardorado.notification.adapter.out.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.json.JsonMapper;

import com.fardorado.notification.application.port.out.WebhookDeliveryCommand;
import com.fardorado.notification.application.port.out.WebhookDeliveryResult;
import com.fardorado.notification.domain.model.notification.EventType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WebhookChannelClientImplTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger LAST_STATUS = new AtomicInteger(200);
    private static final StringBuilder LAST_RECEIVED_BODY = new StringBuilder();

    private final WebhookChannelClientImpl client =
            new WebhookChannelClientImpl(JsonMapper.builder().build());

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            LAST_RECEIVED_BODY.setLength(0);
            LAST_RECEIVED_BODY.append(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            respond(exchange, LAST_STATUS.get());
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void shouldClassifySuccessFor2xx() {
        LAST_STATUS.set(200);

        WebhookDeliveryResult result = client.deliver(command(null));

        assertThat(result.successful()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(LAST_RECEIVED_BODY.toString()).contains("\"event_id\":\"EVT001\"");
        assertThat(LAST_RECEIVED_BODY.toString()).contains("\"event_type\":\"credit_card_payment\"");
    }

    @Test
    void shouldPropagateCorrelationIdHeaderAndBody() {
        LAST_STATUS.set(200);

        client.deliver(command("corr-42"));

        assertThat(LAST_RECEIVED_BODY.toString()).contains("\"correlation_id\":\"corr-42\"");
    }

    @Test
    void shouldClassifyPermanentFailureFor4xx() {
        LAST_STATUS.set(404);

        WebhookDeliveryResult result = client.deliver(command(null));

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryResult.DeliveryOutcome.PERMANENT_FAILURE);
        assertThat(result.errorMessage()).contains("HTTP 404");
    }

    @Test
    void shouldClassifyRetryableFailureFor5xx() {
        LAST_STATUS.set(500);

        WebhookDeliveryResult result = client.deliver(command(null));

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryResult.DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorMessage()).contains("HTTP 500");
    }

    @Test
    void shouldClassifyRetryableFailureFor429() {
        LAST_STATUS.set(429);

        WebhookDeliveryResult result = client.deliver(command(null));

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryResult.DeliveryOutcome.RETRYABLE_FAILURE);
    }

    @Test
    void shouldClassifyRetryableFailureForConnectionFailure() {
        WebhookDeliveryCommand command = new WebhookDeliveryCommand(
                "http://127.0.0.1:1/unreachable", "EVT001", EventType.CREDIT_CARD_PAYMENT,
                1, null, "content");

        WebhookDeliveryResult result = client.deliver(command);

        assertThat(result.outcome()).isEqualTo(WebhookDeliveryResult.DeliveryOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorMessage()).contains("webhook call failed");
    }

    private WebhookDeliveryCommand command(String correlationId) {
        return new WebhookDeliveryCommand(
                URI.create(baseUrl).toString(),
                "EVT001",
                EventType.CREDIT_CARD_PAYMENT,
                1,
                correlationId,
                "{\"content\":\"payment received\"}");
    }
}
