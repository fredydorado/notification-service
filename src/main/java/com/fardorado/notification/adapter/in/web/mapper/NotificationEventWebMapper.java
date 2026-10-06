package com.fardorado.notification.adapter.in.web.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.fardorado.notification.adapter.in.web.dto.DeliveryAttemptResponseDto;
import com.fardorado.notification.adapter.in.web.dto.NotificationEventDetailResponseDto;
import com.fardorado.notification.adapter.in.web.dto.NotificationEventSummaryResponseDto;
import com.fardorado.notification.adapter.in.web.dto.PagedResponseDto;
import com.fardorado.notification.adapter.in.web.dto.ReplayNotificationEventResponseDto;
import com.fardorado.notification.application.result.DeliveryAttemptResult;
import com.fardorado.notification.application.result.NotificationEventDetailResult;
import com.fardorado.notification.application.result.NotificationEventSummaryResult;
import com.fardorado.notification.application.result.PagedResult;
import com.fardorado.notification.application.result.ReplayNotificationEventResult;

/**
 * Maps application results onto the REST response contract, keeping the API
 * shape independent of the application and domain models.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface NotificationEventWebMapper {

    NotificationEventSummaryResponseDto toDto(NotificationEventSummaryResult result);

    DeliveryAttemptResponseDto toDto(DeliveryAttemptResult result);

    NotificationEventDetailResponseDto toDto(NotificationEventDetailResult result);

    ReplayNotificationEventResponseDto toDto(ReplayNotificationEventResult result);

    List<NotificationEventSummaryResponseDto> toSummaryDtos(List<NotificationEventSummaryResult> results);

    default PagedResponseDto<NotificationEventSummaryResponseDto> toDto(
            PagedResult<NotificationEventSummaryResult> result) {
        return new PagedResponseDto<>(
                toSummaryDtos(result.items()),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }
}
