package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fardorado.notification.adapter.out.persistence.entity.SubscriptionEntity;
import com.fardorado.notification.adapter.out.persistence.mapper.SubscriptionPersistenceMapper;
import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationChannel;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

@Component
public class SubscriptionRepositoryImpl implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpaRepository;
    private final SubscriptionPersistenceMapper mapper;

    public SubscriptionRepositoryImpl(SubscriptionJpaRepository jpaRepository, SubscriptionPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Subscription save(Subscription subscription) {
        SubscriptionEntity entity = mapper.toEntity(subscription);
        SubscriptionEntity saved = jpaRepository.saveAndFlush(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Subscription> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<Subscription> findByEventTypeAndChannelAndStatus(
            EventType eventType,
            NotificationChannel channel,
            SubscriptionStatus status) {
        return jpaRepository.findByEventTypeAndChannelAndStatus(eventType, channel, status).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
