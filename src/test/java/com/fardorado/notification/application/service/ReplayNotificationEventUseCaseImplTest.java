package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import com.fardorado.notification.application.command.ReplayNotificationEventCommand;
import com.fardorado.notification.application.exception.ErrorCode;
import com.fardorado.notification.application.exception.NotificationEventNotReplayableException;
import com.fardorado.notification.application.exception.ResourceConflictException;
import com.fardorado.notification.application.exception.ResourceNotFoundException;
import com.fardorado.notification.application.result.ReplayNotificationEventResult;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

class ReplayNotificationEventUseCaseImplTest {

    private final FakeNotificationEventRepository notificationEventRepository =
            new FakeNotificationEventRepository();

    private final ReplayNotificationEventUseCaseImpl useCase =
            new ReplayNotificationEventUseCaseImpl(notificationEventRepository);

    @Test
    void shouldRequeueFailedEventAsPending() {
        persistEvent("evt-failed", "client-1", NotificationEventStatus.FAILED);

        ReplayNotificationEventResult result = useCase.replayNotificationEvent(
                new ReplayNotificationEventCommand("client-1", "evt-failed"));

        assertThat(result.notificationEventId()).isEqualTo("evt-failed");
        assertThat(result.status()).isEqualTo(NotificationEventStatus.PENDING);
        assertThat(notificationEventRepository.findByEventId("evt-failed").orElseThrow().getStatus())
                .isEqualTo(NotificationEventStatus.PENDING);
    }

    @Test
    void shouldRejectReplayOfCompletedEvent() {
        persistEvent("evt-completed", "client-1", NotificationEventStatus.COMPLETED);

        assertThatThrownBy(() -> useCase.replayNotificationEvent(
                new ReplayNotificationEventCommand("client-1", "evt-completed")))
                .isInstanceOf(NotificationEventNotReplayableException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void shouldRejectReplayOfEventCurrentlyBeingProcessed() {
        persistEvent("evt-delivering", "client-1", NotificationEventStatus.DELIVERING);
        persistEvent("evt-pending", "client-1", NotificationEventStatus.PENDING);
        persistEvent("evt-retrying", "client-1", NotificationEventStatus.RETRY_SCHEDULED);

        for (String eventId : new String[] {"evt-delivering", "evt-pending", "evt-retrying"}) {
            assertThatThrownBy(() -> useCase.replayNotificationEvent(
                    new ReplayNotificationEventCommand("client-1", eventId)))
                    .isInstanceOf(NotificationEventNotReplayableException.class);
        }
    }

    @Test
    void shouldCarryTheNotReplayableErrorCode() {
        persistEvent("evt-completed", "client-1", NotificationEventStatus.COMPLETED);

        assertThatThrownBy(() -> useCase.replayNotificationEvent(
                new ReplayNotificationEventCommand("client-1", "evt-completed")))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ResourceConflictException.class))
                .extracting(ResourceConflictException::getErrorCode)
                .isEqualTo(ErrorCode.NOTIFICATION_EVENT_NOT_REPLAYABLE);
    }

    @Test
    void shouldNotFindAnotherClientsEvent() {
        persistEvent("evt-theirs", "client-2", NotificationEventStatus.FAILED);

        assertThatThrownBy(() -> useCase.replayNotificationEvent(
                new ReplayNotificationEventCommand("client-1", "evt-theirs")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldNotFindAnUnknownEvent() {
        assertThatThrownBy(() -> useCase.replayNotificationEvent(
                new ReplayNotificationEventCommand("client-1", "evt-unknown")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * A concurrent replay that already moved the event loses the optimistic
     * lock; it must surface as a conflict rather than replaying a second time.
     */
    @Test
    void shouldReportConflictWhenAnotherRequestAlreadyReplayedTheEvent() {
        persistEvent("evt-raced", "client-1", NotificationEventStatus.FAILED);
        notificationEventRepository.onSave = event -> {
            throw new OptimisticLockingFailureException("stale version");
        };

        assertThatThrownBy(() -> useCase.replayNotificationEvent(
                new ReplayNotificationEventCommand("client-1", "evt-raced")))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("already being replayed");
    }

    private void persistEvent(String eventId, String clientId, NotificationEventStatus status) {
        NotificationEvent event = new NotificationEvent(
                null, eventId, EventType.CREDIT_CARD_PAYMENT, 1, "corr-1",
                status, "{}", 1L, null, null, null);
        notificationEventRepository.save(event);
        notificationEventRepository.stageOwnership(eventId, clientId);
    }
}
