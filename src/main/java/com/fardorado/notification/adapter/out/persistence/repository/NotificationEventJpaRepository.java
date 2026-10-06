package com.fardorado.notification.adapter.out.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.fardorado.notification.adapter.out.persistence.entity.NotificationEventEntity;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

public interface NotificationEventJpaRepository extends JpaRepository<NotificationEventEntity, Long> {

    Optional<NotificationEventEntity> findByEventId(String eventId);

    /**
     * Selects events that are ready for delivery work, oldest first, with
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} so that concurrent workers or
     * service instances never claim the same event. The row locks are held
     * until the surrounding transaction commits, so this must be called
     * inside a transaction.
     *
     * <p>Claimable events are {@code PENDING} events, due
     * {@code RETRY_SCHEDULED} events (next attempt time arrived or unset) and
     * stale {@code DELIVERING} events (last update older than the delivery
     * lease threshold) that must be recovered.</p>
     *
     * <p>{@code jakarta.persistence.lock.timeout = -2} instructs Hibernate to
     * emit {@code FOR UPDATE SKIP LOCKED} on PostgreSQL.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e
              from NotificationEventEntity e
             where e.status = :pendingStatus
                or (e.status = :retryScheduledStatus
                    and (e.nextAttemptAt is null or e.nextAttemptAt <= :now))
                or (e.status = :deliveringStatus and e.updatedAt <= :staleThreshold)
             order by e.createdAt asc
            """)
    List<NotificationEventEntity> findClaimableEvents(
            @Param("pendingStatus") NotificationEventStatus pendingStatus,
            @Param("retryScheduledStatus") NotificationEventStatus retryScheduledStatus,
            @Param("deliveringStatus") NotificationEventStatus deliveringStatus,
            @Param("now") Instant now,
            @Param("staleThreshold") Instant staleThreshold,
            Pageable pageable);
}
