package com.fardorado.notification.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.fardorado.notification.adapter.out.persistence.entity.NotificationEventEntity;
import com.fardorado.notification.domain.model.notification.NotificationEvent;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface NotificationEventPersistenceMapper {

    NotificationEventEntity toEntity(NotificationEvent notificationEvent);

    NotificationEvent toDomain(NotificationEventEntity entity);
}
