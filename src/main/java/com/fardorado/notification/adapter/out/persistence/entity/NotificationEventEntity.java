package com.fardorado.notification.adapter.out.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.fardorado.notification.adapter.out.persistence.converter.EventTypeConverter;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "notification_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notificationEventSeq")
    @SequenceGenerator(name = "notificationEventSeq", sequenceName = "notification_event_seq", allocationSize = 50)
    @Column(name = "id")
    private Long id;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Convert(converter = EventTypeConverter.class)
    @Column(name = "event_type", nullable = false, length = 50)
    private EventType eventType;

    @Column(name = "event_version", nullable = false)
    private Integer eventVersion;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private NotificationEventStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
