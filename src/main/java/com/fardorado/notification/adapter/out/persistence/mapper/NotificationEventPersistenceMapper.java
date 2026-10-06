package com.fardorado.notification.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.fardorado.notification.adapter.out.persistence.entity.NotificationEventEntity;
import com.fardorado.notification.domain.model.notification.NotificationEvent;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface NotificationEventPersistenceMapper {

    /**
     * {@code createdAt} and {@code updatedAt} are owned by Hibernate
     * ({@code @CreationTimestamp} / {@code @UpdateTimestamp}); the domain
     * carries {@code createdAt} for reading only and must never write it back.
     */
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    NotificationEventEntity toEntity(NotificationEvent notificationEvent);

    NotificationEvent toDomain(NotificationEventEntity entity);
}
