package com.fardorado.notification.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.fardorado.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DeliveryAttemptPersistenceMapper {

    /**
     * The {@code notificationEvent} association is deliberately not mapped by
     * MapStruct; the repository adapter wires it through a lazy entity
     * reference to avoid an extra database fetch.
     */
    @Mapping(target = "notificationEvent", ignore = true)
    DeliveryAttemptEntity toEntity(DeliveryAttempt deliveryAttempt);

    @Mapping(source = "notificationEvent.id", target = "notificationEventId")
    DeliveryAttempt toDomain(DeliveryAttemptEntity entity);
}
