package com.fardorado.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fardorado.notification.application.port.out.SubscriptionRepository;
import com.fardorado.notification.domain.model.notification.EventType;
import com.fardorado.notification.domain.model.notification.NotificationChannel;
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubscriptionPersistenceIntTest {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindSubscription() {
        Subscription saved = subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-1",
                EventType.CREDIT_CARD_PAYMENT,
                NotificationChannel.WEBHOOK,
                "https://example.com/webhook"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0);

        Subscription loaded = subscriptionRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getSubscriberId()).isEqualTo("subscriber-1");
        assertThat(loaded.getEventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(loaded.getChannel()).isEqualTo(NotificationChannel.WEBHOOK);
        assertThat(loaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(loaded.getEndpoint()).isEqualTo("https://example.com/webhook");
    }

    @Test
    void shouldPersistEventTypeAsLowerCaseSnakeCase() {
        Subscription saved = subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-2",
                EventType.CASH_WITHDRAWAL,
                NotificationChannel.WEBHOOK,
                "https://example.com/webhook"));

        String storedEventType = jdbcTemplate.queryForObject(
                "SELECT event_type FROM subscription WHERE id = ?", String.class, saved.getId());

        assertThat(storedEventType).isEqualTo("cash_withdrawal");
    }

    @Test
    void shouldFindSubscriptionsByEventTypeChannelAndStatus() {
        subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-3",
                EventType.CREDIT_CARD_PAYMENT,
                NotificationChannel.WEBHOOK,
                "https://a.example.com"));
        subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-4",
                EventType.CREDIT_CARD_PAYMENT,
                NotificationChannel.WEBHOOK,
                "https://b.example.com"));
        subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-5",
                EventType.CASH_WITHDRAWAL,
                NotificationChannel.WEBHOOK,
                "https://c.example.com"));

        List<Subscription> activeCreditCardPayments = subscriptionRepository.findByEventTypeAndChannelAndStatus(
                EventType.CREDIT_CARD_PAYMENT, NotificationChannel.WEBHOOK, SubscriptionStatus.ACTIVE);

        assertThat(activeCreditCardPayments)
                .extracting(Subscription::getSubscriberId)
                .contains("subscriber-3", "subscriber-4")
                .doesNotContain("subscriber-5");
    }

    @Test
    void shouldRejectDuplicateSubscriberEventTypeChannelCombination() {
        subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-6",
                EventType.CREDIT_TRANSFER,
                NotificationChannel.WEBHOOK,
                "https://a.example.com"));

        assertThatThrownBy(() -> subscriptionRepository.save(Subscription.newSubscription(
                        "subscriber-6",
                        EventType.CREDIT_TRANSFER,
                        NotificationChannel.WEBHOOK,
                        "https://b.example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectConcurrentModificationsViaOptimisticLocking() {
        Subscription saved = subscriptionRepository.save(Subscription.newSubscription(
                "subscriber-7",
                EventType.CREDIT_CARD_PAYMENT,
                NotificationChannel.WEBHOOK,
                "https://example.com/webhook"));

        Subscription firstLoad = subscriptionRepository.findById(saved.getId()).orElseThrow();
        Subscription secondLoad = subscriptionRepository.findById(saved.getId()).orElseThrow();

        firstLoad.deactivate();
        subscriptionRepository.save(firstLoad);

        secondLoad.deactivate();
        assertThatThrownBy(() -> subscriptionRepository.save(secondLoad))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
