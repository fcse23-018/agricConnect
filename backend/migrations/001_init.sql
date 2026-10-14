-- ============================================================================
-- AgriConnect · 001_init.sql
-- Extensions, enum types, users, authentication and idempotency.
-- PostgreSQL 15+
-- ============================================================================
BEGIN;

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";     -- case-insensitive email

-- ---------------------------------------------------------------------------
-- Enum types (mirror the documented domain enums exactly)
-- ---------------------------------------------------------------------------
CREATE TYPE user_role      AS ENUM ('FARMER','FARM_EMPLOYEE','BUYER');
CREATE TYPE account_status AS ENUM ('REGISTERED','UNVERIFIED','VERIFIED','SUSPENDED','PENDING_DELETION','DELETED');
CREATE TYPE sync_status    AS ENUM ('PENDING','SYNCED','CONFLICT','FAILED');
CREATE TYPE listing_status AS ENUM ('DRAFT','PENDING_PUBLISH','SYNCING','RETRY','PUBLISHED','SOLD');
CREATE TYPE order_status   AS ENUM ('PENDING','CONFIRMED','COMPLETED','CANCELLED');
CREATE TYPE sync_operation AS ENUM ('CREATE','UPDATE','DELETE');
CREATE TYPE token_purpose  AS ENUM ('PASSWORD_RESET','EMAIL_VERIFICATION','PHONE_VERIFICATION');
CREATE TYPE deletion_state AS ENUM ('PENDING','PROCESSING','COMPLETED','CANCELLED');

-- ---------------------------------------------------------------------------
-- Shared trigger function: keep updated_at honest
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  full_name               text NOT NULL CHECK (char_length(btrim(full_name)) BETWEEN 2 AND 120),
  phone_number            text NOT NULL,
  email                   citext,
  password_hash           text NOT NULL,
  role                    user_role NOT NULL DEFAULT 'FARMER',
  account_status          account_status NOT NULL DEFAULT 'UNVERIFIED',
  avatar_url              text,
  notification_preferences jsonb NOT NULL DEFAULT
      '{"orders":true,"sync":true,"satellite":true,"marketplace":true}'::jsonb,
  verified_at             timestamptz,
  last_login_at           timestamptz,
  failed_login_count      integer NOT NULL DEFAULT 0,
  locked_until            timestamptz,
  deleted_at              timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT users_phone_format CHECK (phone_number ~ '^\+[1-9][0-9]{7,14}$'),
  CONSTRAINT users_email_format CHECK (email IS NULL OR email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$')
);

-- Uniqueness is enforced only for live accounts (soft-deleted rows are anonymised
-- and their identifiers released, but a partial index guards the active set).
CREATE UNIQUE INDEX users_phone_unique ON users (phone_number) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX users_email_unique ON users (lower(email)) WHERE email IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX users_role_idx   ON users (role);
CREATE INDEX users_status_idx ON users (account_status);

CREATE TRIGGER users_set_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- refresh_tokens  (rotation; only the hash is stored)
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash   text NOT NULL,
  device_id    text,
  user_agent   text,
  expires_at   timestamptz NOT NULL,
  revoked_at   timestamptz,
  replaced_by  uuid REFERENCES refresh_tokens(id) ON DELETE SET NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX refresh_tokens_hash_unique ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- verification / recovery tokens (single-use, hashed, short-lived)
-- ---------------------------------------------------------------------------
CREATE TABLE verification_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose      token_purpose NOT NULL,
  token_hash   text NOT NULL,
  destination  text,                       -- masked phone / email the code was sent to
  attempts     integer NOT NULL DEFAULT 0,
  expires_at   timestamptz NOT NULL,
  consumed_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX verification_tokens_hash_unique ON verification_tokens (token_hash);
CREATE INDEX verification_tokens_user_purpose_idx ON verification_tokens (user_id, purpose) WHERE consumed_at IS NULL;

-- ---------------------------------------------------------------------------
-- idempotency (double-tap / retry protection for POSTs)
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
  key              text PRIMARY KEY,
  user_id          uuid,
  endpoint         text NOT NULL,
  request_hash     text,
  response_status  integer,
  response_body    jsonb,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys (created_at);

COMMIT;
