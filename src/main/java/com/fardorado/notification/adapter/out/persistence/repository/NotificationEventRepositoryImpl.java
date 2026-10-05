package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.adapter.out.persistence.entity.NotificationEventEntity;
import com.fardorado.notification.adapter.out.persistence.mapper.NotificationEventPersistenceMapper;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

@Component
public class NotificationEventRepositoryImpl implements NotificationEventRepository {

    private final NotificationEventJpaRepository jpaRepository;
    private final NotificationEventPersistenceMapper mapper;

    public NotificationEventRepositoryImpl(
            NotificationEventJpaRepository jpaRepository, NotificationEventPersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public NotificationEvent save(NotificationEvent notificationEvent) {
        NotificationEventEntity entity = mapper.toEntity(notificationEvent);
        NotificationEventEntity saved = jpaRepository.saveAndFlush(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<NotificationEvent> findById(Long id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    /**
     * Claims events that are eligible for a retry. The underlying query uses
     * {@code FOR UPDATE SKIP LOCKED}, so the call must run inside a
     * transaction; the row locks are held until that transaction commits.
     */
    @Transactional
    @Override
    public List<NotificationEvent> claimRetryEligible(int limit) {
        return jpaRepository
                .findByStatusForClaim(
                        NotificationEventStatus.RETRY_SCHEDULED, PageRequest.of(0, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
