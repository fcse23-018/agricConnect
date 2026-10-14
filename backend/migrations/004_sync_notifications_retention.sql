-- ============================================================================
-- AgriConnect · 004_sync_notifications_retention.sql
-- Notification feed, server-side sync ledger, retention/audit and dev outbox.
-- ============================================================================
BEGIN;

-- ---------------------------------------------------------------------------
-- notifications
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type                text NOT NULL,     -- ORDER_PLACED | ORDER_CONFIRMED | ORDER_REJECTED | NDVI_READY | SYSTEM …
  title               text NOT NULL,
  body                text NOT NULL,
  related_entity_type text,
  related_entity_id   uuid,
  is_read             boolean NOT NULL DEFAULT false,
  created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notifications_user_idx ON notifications (user_id, created_at DESC);
CREATE INDEX notifications_unread_idx ON notifications (user_id) WHERE is_read = false;

-- device push tokens (optional; no push provider configured by default)
CREATE TABLE device_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token       text NOT NULL,
  platform    text NOT NULL DEFAULT 'android',
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, token)
);

-- ---------------------------------------------------------------------------
-- sync_queue_items — server-side ledger of processed push items.
--   Guarantees idempotent /sync/push and provides an audit of offline writes.
-- ---------------------------------------------------------------------------
CREATE TABLE sync_queue_items (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  queue_id         text NOT NULL,        -- device-generated queue id
  device_id        text,
  entity_type      text NOT NULL,
  entity_id        text,
  operation        sync_operation NOT NULL,
  payload          jsonb,
  client_timestamp timestamptz,
  status           sync_status NOT NULL DEFAULT 'PENDING',
  retry_count      integer NOT NULL DEFAULT 0,
  server_entity_id uuid,
  error            text,
  processed_at     timestamptz,
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, queue_id)
);
CREATE INDEX sync_queue_user_status_idx ON sync_queue_items (user_id, status);
CREATE INDEX sync_queue_entity_idx ON sync_queue_items (user_id, entity_type, entity_id);

-- ---------------------------------------------------------------------------
-- account_deletion_requests  (grace period + anonymisation workflow)
-- ---------------------------------------------------------------------------
CREATE TABLE account_deletion_requests (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason         text,
  status         deletion_state NOT NULL DEFAULT 'PENDING',
  requested_at   timestamptz NOT NULL DEFAULT now(),
  effective_at   timestamptz NOT NULL,
  anonymised_at  timestamptz,
  completed_at   timestamptz,
  cancelled_at   timestamptz
);
CREATE INDEX deletion_pending_idx ON account_deletion_requests (status, effective_at);
CREATE UNIQUE INDEX deletion_one_active_per_user ON account_deletion_requests (user_id)
  WHERE status IN ('PENDING','PROCESSING');

-- ---------------------------------------------------------------------------
-- retention_ledger — proves what was retained and why (legal/tax justification)
-- ---------------------------------------------------------------------------
CREATE TABLE retention_ledger (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_user   uuid NOT NULL,          -- no FK: survives user anonymisation
  data_category  text NOT NULL,          -- 'FINANCIAL','ORDERS','AUDIT','MARKETPLACE'
  justification  text NOT NULL,
  retain_until   date NOT NULL,
  pseudonym      text NOT NULL,          -- non-reversible reference used after anonymisation
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX retention_subject_idx ON retention_ledger (subject_user);

-- ---------------------------------------------------------------------------
-- audit_log — security-relevant events. Never contains passwords or tokens.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_user_id uuid,
  action        text NOT NULL,
  entity_type   text,
  entity_id     text,
  metadata      jsonb,
  ip            text,
  user_agent    text,
  created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_actor_idx  ON audit_log (actor_user_id, created_at DESC);
CREATE INDEX audit_action_idx ON audit_log (action, created_at DESC);

-- ---------------------------------------------------------------------------
-- message_outbox — every outbound email/SMS is written here for audit.
-- When no delivery provider is configured this is the *only* destination and is
-- explicitly development-only (see docs/07). Never expose this table publicly.
-- ---------------------------------------------------------------------------
CREATE TABLE message_outbox (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  channel      text NOT NULL CHECK (channel IN ('EMAIL','SMS')),
  destination  text NOT NULL,
  subject      text,
  body         text NOT NULL,
  delivered    boolean NOT NULL DEFAULT false,
  provider     text NOT NULL DEFAULT 'OUTBOX',
  provider_ref text,
  error        text,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX message_outbox_dest_idx ON message_outbox (destination, created_at DESC);

COMMIT;
