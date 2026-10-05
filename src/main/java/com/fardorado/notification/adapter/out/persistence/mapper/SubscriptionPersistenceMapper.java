package com.fardorado.notification.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.fardorado.notification.adapter.out.persistence.entity.SubscriptionEntity;
import com.fardorado.notification.domain.model.subscription.Subscription;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SubscriptionPersistenceMapper {

    SubscriptionEntity toEntity(Subscription subscription);

    Subscription toDomain(SubscriptionEntity entity);
}
