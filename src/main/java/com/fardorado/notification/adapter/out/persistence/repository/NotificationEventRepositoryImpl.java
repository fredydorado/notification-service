package com.fardorado.notification.adapter.out.persistence.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fardorado.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;
import com.fardorado.notification.adapter.out.persistence.entity.NotificationEventEntity;
import com.fardorado.notification.adapter.out.persistence.entity.SubscriptionEntity;
import com.fardorado.notification.adapter.out.persistence.mapper.NotificationEventPersistenceMapper;
import com.fardorado.notification.application.port.out.NotificationEventRepository;
import com.fardorado.notification.application.result.NotificationEventSummaryResult;
import com.fardorado.notification.application.result.PagedResult;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

@Component
@RequiredArgsConstructor
public class NotificationEventRepositoryImpl implements NotificationEventRepository {

    private final NotificationEventJpaRepository jpaRepository;
    private final DeliveryAttemptJpaRepository deliveryAttemptJpaRepository;
    private final NotificationEventPersistenceMapper mapper;

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

    @Override
    public Optional<NotificationEvent> findByEventId(String eventId) {
        return jpaRepository.findByEventId(eventId).map(mapper::toDomain);
    }

    @Override
    public Optional<NotificationEvent> findByEventIdAndClientId(String eventId, String clientId) {
        return jpaRepository.findByEventIdAndClientId(eventId, clientId).map(mapper::toDomain);
    }

    /**
     * Reads one page of the client's events, then enriches it with attempt
     * data fetched in a single additional query, so the number of queries
     * stays constant regardless of page size.
     */
    @Transactional(readOnly = true)
    @Override
    public PagedResult<NotificationEventSummaryResult> findSummariesByClientId(
            String clientId,
            Instant from,
            Instant to,
            NotificationEventStatus status,
            int page,
            int size) {
        Page<NotificationEventEntity> events = jpaRepository.findAll(
                ownedBy(clientId, from, to, status),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<Long, List<DeliveryAttemptEntity>> attemptsByEventId = attemptsOf(events.getContent());

        List<NotificationEventSummaryResult> items = events.getContent().stream()
                .map(event -> toSummary(event, attemptsByEventId.getOrDefault(event.getId(), List.of())))
                .toList();

        return PagedResult.of(items, events.getNumber(), events.getSize(), events.getTotalElements());
    }

    /**
     * Builds the filter as a specification rather than a query with
     * {@code (:param is null or ...)} branches: an unsupplied filter is left
     * out of the SQL entirely, which both keeps the plan tight and avoids
     * PostgreSQL being unable to infer the type of an untyped null bind.
     *
     * <p>Ownership is expressed as a subquery over {@code subscription}
     * because {@code subscriptionId} is a plain scalar with no association
     * to navigate.</p>
     */
    private Specification<NotificationEventEntity> ownedBy(
            String clientId, Instant from, Instant to, NotificationEventStatus status) {
        return (root, query, builder) -> {
            Subquery<Long> ownedSubscriptions = query.subquery(Long.class);
            Root<SubscriptionEntity> subscription = ownedSubscriptions.from(SubscriptionEntity.class);
            ownedSubscriptions.select(subscription.get("id"))
                    .where(builder.equal(subscription.get("clientId"), clientId));

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("subscriptionId").in(ownedSubscriptions));
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<Long, List<DeliveryAttemptEntity>> attemptsOf(List<NotificationEventEntity> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        List<Long> eventIds = events.stream().map(NotificationEventEntity::getId).toList();
        return deliveryAttemptJpaRepository
                .findByNotificationEventIdInOrderByAttemptNumberAsc(eventIds)
                .stream()
                .collect(Collectors.groupingBy(attempt -> attempt.getNotificationEvent().getId()));
    }

    /**
     * Attempts arrive ordered by attempt number, so the last element is the
     * most recent attempt. Its HTTP status is null when that attempt never
     * received a response.
     */
    private NotificationEventSummaryResult toSummary(
            NotificationEventEntity event, List<DeliveryAttemptEntity> attempts) {
        Integer lastHttpStatus = attempts.isEmpty() ? null : attempts.getLast().getHttpStatus();
        return new NotificationEventSummaryResult(
                event.getEventId(),
                event.getEventType(),
                event.getCreatedAt(),
                event.getStatus(),
                attempts.size(),
                lastHttpStatus);
    }

    /**
     * Claims events that are ready for delivery work. The underlying query
     * uses {@code FOR UPDATE SKIP LOCKED}, so the call must run inside a
     * transaction; the row locks are held until that transaction commits.
     */
    @Transactional
    @Override
    public List<NotificationEvent> claimDeliverable(int limit, Instant now, Instant staleThreshold) {
        return jpaRepository
                .findClaimableEvents(
                        NotificationEventStatus.PENDING,
                        NotificationEventStatus.RETRY_SCHEDULED,
                        NotificationEventStatus.DELIVERING,
                        now,
                        staleThreshold,
                        PageRequest.of(0, limit))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}
