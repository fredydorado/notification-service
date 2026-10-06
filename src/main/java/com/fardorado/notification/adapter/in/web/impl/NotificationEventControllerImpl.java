package com.fardorado.notification.adapter.in.web.impl;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fardorado.notification.adapter.in.web.NotificationEventController;
import com.fardorado.notification.adapter.in.web.dto.NotificationEventDetailResponseDto;
import com.fardorado.notification.adapter.in.web.dto.NotificationEventSummaryResponseDto;
import com.fardorado.notification.adapter.in.web.dto.PagedResponseDto;
import com.fardorado.notification.adapter.in.web.dto.ReplayNotificationEventResponseDto;
import com.fardorado.notification.adapter.in.web.mapper.NotificationEventWebMapper;
import com.fardorado.notification.application.command.GetNotificationEventCommand;
import com.fardorado.notification.application.command.ListNotificationEventsCommand;
import com.fardorado.notification.application.command.ReplayNotificationEventCommand;
import com.fardorado.notification.application.port.in.GetNotificationEventUseCase;
import com.fardorado.notification.application.port.in.ListNotificationEventsUseCase;
import com.fardorado.notification.application.port.in.ReplayNotificationEventUseCase;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

/**
 * Transport layer only: binds the HTTP request, delegates to an input port
 * and maps the result onto the response contract. The OpenAPI documentation
 * lives on {@link NotificationEventController}.
 *
 * <p>The client identity comes from the {@code X-Client-Id} header; ownership
 * itself is enforced by the use cases, not here.</p>
 */
@RestController
@RequestMapping("/notification_events")
@Validated
public class NotificationEventControllerImpl implements NotificationEventController {

    private static final String CLIENT_ID_HEADER = "X-Client-Id";

    private final ListNotificationEventsUseCase listNotificationEventsUseCase;
    private final GetNotificationEventUseCase getNotificationEventUseCase;
    private final ReplayNotificationEventUseCase replayNotificationEventUseCase;
    private final NotificationEventWebMapper mapper;

    public NotificationEventControllerImpl(
            ListNotificationEventsUseCase listNotificationEventsUseCase,
            GetNotificationEventUseCase getNotificationEventUseCase,
            ReplayNotificationEventUseCase replayNotificationEventUseCase,
            NotificationEventWebMapper mapper) {
        this.listNotificationEventsUseCase = listNotificationEventsUseCase;
        this.getNotificationEventUseCase = getNotificationEventUseCase;
        this.replayNotificationEventUseCase = replayNotificationEventUseCase;
        this.mapper = mapper;
    }

    @GetMapping
    @Override
    public ResponseEntity<PagedResponseDto<NotificationEventSummaryResponseDto>> listNotificationEvents(
            @RequestHeader(CLIENT_ID_HEADER) String clientId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "delivery_status", required = false) NotificationEventStatus deliveryStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "${notification.api.default-page-size:20}") int size) {

        var result = listNotificationEventsUseCase.listNotificationEvents(
                new ListNotificationEventsCommand(clientId, from, to, deliveryStatus, page, size));

        return ResponseEntity.ok(mapper.toDto(result));
    }

    @GetMapping("/{notification_event_id}")
    @Override
    public ResponseEntity<NotificationEventDetailResponseDto> getNotificationEvent(
            @RequestHeader(CLIENT_ID_HEADER) String clientId,
            @PathVariable("notification_event_id") String notificationEventId) {

        var result = getNotificationEventUseCase.getNotificationEvent(
                new GetNotificationEventCommand(clientId, notificationEventId));

        return ResponseEntity.ok(mapper.toDto(result));
    }

    @PostMapping("/{notification_event_id}/replay")
    @Override
    public ResponseEntity<ReplayNotificationEventResponseDto> replayNotificationEvent(
            @RequestHeader(CLIENT_ID_HEADER) String clientId,
            @PathVariable("notification_event_id") String notificationEventId) {

        var result = replayNotificationEventUseCase.replayNotificationEvent(
                new ReplayNotificationEventCommand(clientId, notificationEventId));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapper.toDto(result));
    }
}
