package com.fardorado.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fardorado.notification.application.command.ListNotificationEventsCommand;
import com.fardorado.notification.application.exception.InvalidRequestException;
import com.fardorado.notification.configuration.NotificationApiProperties;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationEvent;
import com.fardorado.notification.domain.model.notification.NotificationEventStatus;

class ListNotificationEventsUseCaseImplTest {

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-05T23:59:59Z");

    private final FakeNotificationEventRepository notificationEventRepository =
            new FakeNotificationEventRepository();

    private final ListNotificationEventsUseCaseImpl useCase = new ListNotificationEventsUseCaseImpl(
            notificationEventRepository, new NotificationApiProperties(20, 100));

    @Test
    void shouldScopeTheQueryToTheRequestingClient() {
        persistEvent("evt-mine", "client-1");
        persistEvent("evt-theirs", "client-2");

        var result = useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", FROM, TO, NotificationEventStatus.FAILED, 0, 20));

        assertThat(result.items()).extracting(summary -> summary.id()).containsExactly("evt-mine");
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void shouldDelegateEveryFilterToTheRepository() {
        useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", FROM, TO, NotificationEventStatus.FAILED, 2, 50));

        assertThat(notificationEventRepository.lastListCriteria)
                .isEqualTo(new FakeNotificationEventRepository.ListCriteria(
                        "client-1", FROM, TO, NotificationEventStatus.FAILED, 2, 50));
    }

    @Test
    void shouldRejectInvertedDateRange() {
        assertThatThrownBy(() -> useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", TO, FROM, null, 0, 20)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("'from' must not be after 'to'");
    }

    @Test
    void shouldAllowAnEqualFromAndTo() {
        assertThatCode(() -> useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", FROM, FROM, null, 0, 20)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAllowAnOpenEndedRange() {
        assertThatCode(() -> useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", null, null, null, 0, 20)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectPageSizeAboveTheConfiguredMaximum() {
        assertThatThrownBy(() -> useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", null, null, null, 0, 101)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("must not exceed 100");
    }

    @Test
    void shouldAcceptPageSizeAtTheConfiguredMaximum() {
        assertThatCode(() -> useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", null, null, null, 0, 100)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotQueryTheRepositoryWhenValidationFails() {
        assertThatThrownBy(() -> useCase.listNotificationEvents(
                new ListNotificationEventsCommand("client-1", TO, FROM, null, 0, 20)))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(notificationEventRepository.lastListCriteria).isNull();
    }

    private void persistEvent(String eventId, String clientId) {
        NotificationEvent event = NotificationEvent.newEvent(
                eventId, EventType.CREDIT_CARD_PAYMENT, 1, null, "{}", 1L);
        notificationEventRepository.save(event);
        notificationEventRepository.stageOwnership(eventId, clientId);
    }
}
