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
import com.fardorado.notification.domain.model.subscription.Subscription;
import com.fardorado.notification.domain.model.subscription.SubscriptionStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "notification.processing.dispatch-enabled=false")
class SubscriptionPersistenceIntTest {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindSubscription() {
        Subscription saved = subscriptionRepository.save(Subscription.newSubscription(
                "client-1", EventType.CREDIT_CARD_PAYMENT, "https://example.com/webhook"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0);

        Subscription loaded = subscriptionRepository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getClientId()).isEqualTo("client-1");
        assertThat(loaded.getEventType()).isEqualTo(EventType.CREDIT_CARD_PAYMENT);
        assertThat(loaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(loaded.getWebhookUrl()).isEqualTo("https://example.com/webhook");
    }

    @Test
    void shouldPersistEventTypeAsLowerCaseSnakeCase() {
        Subscription saved = subscriptionRepository.save(Subscription.newSubscription(
                "client-2", EventType.DEBIT_CARD_WITHDRAWAL, "https://example.com/webhook"));

        String storedEventType = jdbcTemplate.queryForObject(
                "SELECT event_type FROM subscription WHERE id = ?", String.class, saved.getId());

        assertThat(storedEventType).isEqualTo("debit_card_withdrawal");
    }

    @Test
    void shouldFindActiveSubscriptionByClientIdAndEventType() {
        subscriptionRepository.save(Subscription.newSubscription(
                "client-3", EventType.CREDIT_CARD_PAYMENT, "https://a.example.com"));
        Subscription inactive = subscriptionRepository.save(Subscription.newSubscription(
                "client-4", EventType.CREDIT_CARD_PAYMENT, "https://b.example.com"));
        inactive.deactivate();
        subscriptionRepository.save(inactive);

        List<Subscription> activeForClient3 = subscriptionRepository.findByClientIdAndEventTypeAndStatus(
                "client-3", EventType.CREDIT_CARD_PAYMENT, SubscriptionStatus.ACTIVE);

        assertThat(activeForClient3)
                .extracting(Subscription::getClientId)
                .contains("client-3")
                .doesNotContain("client-4");
    }

    @Test
    void shouldRejectDuplicateClientEventTypeCombination() {
        subscriptionRepository.save(Subscription.newSubscription(
                "client-5", EventType.CREDIT_TRANSFER, "https://a.example.com"));

        assertThatThrownBy(() -> subscriptionRepository.save(Subscription.newSubscription(
                        "client-5", EventType.CREDIT_TRANSFER, "https://b.example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRejectConcurrentModificationsViaOptimisticLocking() {
        Subscription saved = subscriptionRepository.save(Subscription.newSubscription(
                "client-6", EventType.CREDIT_CARD_PAYMENT, "https://example.com/webhook"));

        Subscription firstLoad = subscriptionRepository.findById(saved.getId()).orElseThrow();
        Subscription secondLoad = subscriptionRepository.findById(saved.getId()).orElseThrow();

        firstLoad.deactivate();
        subscriptionRepository.save(firstLoad);

        secondLoad.deactivate();
        assertThatThrownBy(() -> subscriptionRepository.save(secondLoad))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
