package com.fardorado.notification.adapter.out.persistence.repository;

import java.util.List;

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

    /**
     * Selects events eligible for (re)delivery, oldest first, with
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} so that concurrent workers
     * never claim the same event. The row locks are held until the surrounding
     * transaction commits, so this must be called inside a transaction.
     *
     * <p>{@code jakarta.persistence.lock.timeout = -2} instructs Hibernate to
     * emit {@code FOR UPDATE SKIP LOCKED} on PostgreSQL.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e
              from NotificationEventEntity e
             where e.status = :status
             order by e.createdAt asc
            """)
    List<NotificationEventEntity> findByStatusForClaim(
            @Param("status") NotificationEventStatus status,
            Pageable pageable);
}
