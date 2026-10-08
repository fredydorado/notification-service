-- Subscriptions for the example events in this folder (see README "Publish with Kafka UI").
--
--   docker exec -i notification-postgres psql -U postgres -d notification_service < samples/kafka-ui/00_subscriptions.sql
--
-- Safe to re-run. event_type is stored in its wire form (lowercase).
--
--   CLIENT-UI-A  three subscriptions with a reachable webhook -> deliveries end COMPLETED
--                (run a receiver on :9100, e.g. the webhook_receiver.py from the README)
--   CLIENT-UI-B  one subscription whose webhook is unreachable -> RETRY_SCHEDULED, then FAILED
--   CLIENT-UI-C  deliberately has no subscription -> events are stored FAILED, no owner

INSERT INTO subscription (client_id, event_type, status, webhook_url, created_at, updated_at)
VALUES ('CLIENT-UI-A', 'credit_card_payment', 'ACTIVE', 'http://localhost:9100/webhook', now(), now()),
       ('CLIENT-UI-A', 'debit_purchase',      'ACTIVE', 'http://localhost:9100/webhook', now(), now()),
       ('CLIENT-UI-A', 'credit_refund',       'ACTIVE', 'http://localhost:9100/webhook', now(), now()),
       ('CLIENT-UI-B', 'credit_transfer',     'ACTIVE', 'http://localhost:9999/unreachable', now(), now())
ON CONFLICT (client_id, event_type)
DO UPDATE SET status = 'ACTIVE', webhook_url = EXCLUDED.webhook_url, updated_at = now();
