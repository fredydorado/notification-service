package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fardorado.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;
import com.fardorado.notification.adapter.out.persistence.mapper.DeliveryAttemptPersistenceMapper;
import com.fardorado.notification.application.port.out.DeliveryAttemptRepository;
import com.fardorado.notification.domain.model.delivery.DeliveryAttempt;

@Component
public class DeliveryAttemptRepositoryImpl implements DeliveryAttemptRepository {

    private final DeliveryAttemptJpaRepository jpaRepository;
    private final NotificationEventJpaRepository notificationEventJpaRepository;
    private final DeliveryAttemptPersistenceMapper mapper;

    public DeliveryAttemptRepositoryImpl(
            DeliveryAttemptJpaRepository jpaRepository,
            NotificationEventJpaRepository notificationEventJpaRepository,
            DeliveryAttemptPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.notificationEventJpaRepository = notificationEventJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public DeliveryAttempt save(DeliveryAttempt deliveryAttempt) {
        DeliveryAttemptEntity entity = mapper.toEntity(deliveryAttempt);
        entity.setNotificationEvent(
                notificationEventJpaRepository.getReferenceById(deliveryAttempt.getNotificationEventId()));
        DeliveryAttemptEntity saved = jpaRepository.saveAndFlush(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public List<DeliveryAttempt> findByNotificationEventIdOrderByAttemptNumber(Long notificationEventId) {
        return jpaRepository.findByNotificationEventIdOrderByAttemptNumberAsc(notificationEventId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
