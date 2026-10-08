-- Test data for postman/notification-service.postman_collection.json.
--
-- Mirrors what NotificationEventControllerIntTest seeds through the repositories:
-- two clients, each subscribed to credit_card_payment, and events in the statuses
-- the scenarios need. Safe to re-run: it first removes every API-* event (and its
-- delivery attempts) and so resets the data that the Replay folder consumes.
--
--   docker exec -i notification-postgres psql -U postgres -d notification_service < postman/seed.sql
--
-- Explicit ids in the 900000+ range are used on purpose: the application draws ids
-- from sequences in blocks of 50, so drawing from them here could collide with ids
-- Hibernate has already reserved.

BEGIN;

DELETE FROM delivery_attempt
 WHERE notification_event_id IN (SELECT id FROM notification_event WHERE event_id LIKE 'API-%');
DELETE FROM notification_event WHERE event_id LIKE 'API-%';

-- event_type is stored in its wire form (lowercase); an upper-case value never matches.
INSERT INTO subscription (id, client_id, event_type, status, webhook_url, created_at, updated_at, version)
VALUES (900001, 'CLIENT-API-A', 'credit_card_payment', 'ACTIVE', 'https://example.com/CLIENT-API-A', now(), now(), 0),
       (900002, 'CLIENT-API-B', 'credit_card_payment', 'ACTIVE', 'https://example.com/CLIENT-API-B', now(), now(), 0)
ON CONFLICT (client_id, event_type)
DO UPDATE SET status = 'ACTIVE', webhook_url = EXCLUDED.webhook_url, updated_at = now();

INSERT INTO notification_event
       (id, event_id, event_type, event_version, correlation_id, status, payload,
        subscription_id, next_attempt_at, created_at, updated_at, version)
SELECT e.id, e.event_id, 'credit_card_payment', 1, 'corr-' || e.event_id, e.status,
       '{"content": "api test"}'::jsonb, s.id, NULL, now(), now(), 0
  FROM (VALUES
          (900001, 'API-L1',          'CLIENT-API-A', 'FAILED'),     -- list: own event
          (900002, 'API-L2',          'CLIENT-API-B', 'FAILED'),     -- list: another client's event
          (900003, 'API-P1',          'CLIENT-API-A', 'FAILED'),     -- pagination
          (900004, 'API-L3',          'CLIENT-API-A', 'FAILED'),     -- attempt count / last HTTP status
          (900005, 'API-F-FAILED',    'CLIENT-API-A', 'FAILED'),     -- delivery_status filter
          (900006, 'API-F-PENDING',   'CLIENT-API-A', 'PENDING'),
          (900007, 'API-D1',          'CLIENT-API-A', 'FAILED'),     -- creation-date filter
          (900008, 'API-D-OK',        'CLIENT-API-A', 'FAILED'),     -- detail with attempt history
          (900009, 'API-D-THEIRS',    'CLIENT-API-B', 'FAILED'),     -- detail: another client's event
          (900010, 'API-R-FAILED',    'CLIENT-API-A', 'FAILED'),     -- replay accepted
          (900011, 'API-R-COMPLETED', 'CLIENT-API-A', 'COMPLETED'),  -- replay rejected (409)
          (900012, 'API-R-THEIRS',    'CLIENT-API-B', 'FAILED'),     -- replay: another client's event
          (900013, 'API-R-TWICE',     'CLIENT-API-A', 'FAILED')      -- replay twice: second is a conflict
       ) AS e (id, event_id, client_id, status)
  JOIN subscription s ON s.client_id = e.client_id AND s.event_type = 'credit_card_payment';

-- Two failed attempts (HTTP 500, then 503) for the events whose history is asserted.
INSERT INTO delivery_attempt
       (id, notification_event_id, status, attempt_number, error_message, http_status,
        created_at, completed_at, version)
SELECT a.id, ne.id, 'FAILED', a.attempt_number,
       'webhook endpoint returned HTTP ' || a.http_status, a.http_status, now(), now(), 0
  FROM (VALUES
          (900001, 'API-L3',   1, 500),
          (900002, 'API-L3',   2, 503),
          (900003, 'API-D-OK', 1, 500),
          (900004, 'API-D-OK', 2, 503)
       ) AS a (id, event_id, attempt_number, http_status)
  JOIN notification_event ne ON ne.event_id = a.event_id;

COMMIT;
