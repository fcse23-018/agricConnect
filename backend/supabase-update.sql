-- ============================================================================
-- AgriConnect · Supabase schema update (idempotent)
-- ============================================================================
--
-- WHAT THIS IS
--   A single, re-runnable script that brings a Supabase PostgreSQL database up
--   to the state described by backend/migrations/001..005. It is safe to run on
--   a database in ANY state:
--     * empty database        → creates the whole schema
--     * fully migrated        → no-ops (every statement is guarded)
--     * partially migrated    → applies only what is missing
--   The whole script runs in ONE transaction: if any statement fails, nothing
--   is committed.
--
-- HOW TO RUN
--   1. Supabase Dashboard → your project → SQL Editor → New query
--   2. Paste this entire file and press Run
--   3. Run the verification query at the bottom — "missing_tables" must be empty
--
-- WHAT IT DOES NOT DO
--   * It does not insert seed/demo data (use `npm run seed` from backend/).
--   * It never drops or rewrites an existing column, so no data is lost.
--   * It does not change any row contents.
--
-- AFTER RUNNING
--   All five migration filenames are recorded in schema_migrations, so a later
--   `npm run migrate` against the same database will skip them instead of
--   failing with "already exists".
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Extensions
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";     -- case-insensitive email

-- ---------------------------------------------------------------------------
-- 2. Enum types (CREATE TYPE has no IF NOT EXISTS, so create each one only
--    when it is not already present)
-- ---------------------------------------------------------------------------
DO $types$
DECLARE
  t RECORD;
BEGIN
  FOR t IN
    SELECT * FROM (VALUES
      ('user_role',       'ENUM (''FARMER'',''FARM_EMPLOYEE'',''BUYER'')'),
      ('account_status',  'ENUM (''REGISTERED'',''UNVERIFIED'',''VERIFIED'',''SUSPENDED'',''PENDING_DELETION'',''DELETED'')'),
      ('sync_status',     'ENUM (''PENDING'',''SYNCED'',''CONFLICT'',''FAILED'')'),
      ('listing_status',  'ENUM (''DRAFT'',''PENDING_PUBLISH'',''SYNCING'',''RETRY'',''PUBLISHED'',''SOLD'')'),
      ('order_status',    'ENUM (''PENDING'',''CONFIRMED'',''COMPLETED'',''CANCELLED'')'),
      ('sync_operation',  'ENUM (''CREATE'',''UPDATE'',''DELETE'')'),
      ('token_purpose',   'ENUM (''PASSWORD_RESET'',''EMAIL_VERIFICATION'',''PHONE_VERIFICATION'')'),
      ('deletion_state',  'ENUM (''PENDING'',''PROCESSING'',''COMPLETED'',''CANCELLED'')')
    ) AS v(type_name, type_decl)
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = t.type_name) THEN
      EXECUTE format('CREATE TYPE %I AS %s', t.type_name, t.type_decl);
    END IF;
  END LOOP;
END
$types$;

-- ---------------------------------------------------------------------------
-- 3. Shared trigger function: keep updated_at honest
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $fn$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 4a. 001_init — users, authentication, idempotency
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
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

CREATE UNIQUE INDEX IF NOT EXISTS users_phone_unique ON users (phone_number) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS users_email_unique ON users (lower(email)) WHERE email IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS users_role_idx   ON users (role);
CREATE INDEX IF NOT EXISTS users_status_idx ON users (account_status);

CREATE TABLE IF NOT EXISTS refresh_tokens (
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
CREATE UNIQUE INDEX IF NOT EXISTS refresh_tokens_hash_unique ON refresh_tokens (token_hash);
CREATE INDEX IF NOT EXISTS refresh_tokens_user_idx ON refresh_tokens (user_id) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS verification_tokens (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose      token_purpose NOT NULL,
  token_hash   text NOT NULL,
  destination  text,
  attempts     integer NOT NULL DEFAULT 0,
  expires_at   timestamptz NOT NULL,
  consumed_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS verification_tokens_hash_unique ON verification_tokens (token_hash);
CREATE INDEX IF NOT EXISTS verification_tokens_user_purpose_idx ON verification_tokens (user_id, purpose) WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS idempotency_keys (
  key              text PRIMARY KEY,
  user_id          uuid,
  endpoint         text NOT NULL,
  request_hash     text,
  response_status  integer,
  response_body    jsonb,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idempotency_keys_created_idx ON idempotency_keys (created_at);

-- ---------------------------------------------------------------------------
-- 4b. 002_domain — plots, harvests, livestock, finance, satellite
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS farm_plots (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id        text,
  plot_name        text NOT NULL CHECK (char_length(btrim(plot_name)) BETWEEN 1 AND 120),
  area_hectares    numeric(12,4) CHECK (area_hectares IS NULL OR area_hectares >= 0),
  geojson_boundary jsonb,
  centroid_lat     double precision,
  centroid_lng     double precision,
  notes            text,
  image_url        text,                    -- /uploads path of a plot photo
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS farm_plots_client_unique ON farm_plots (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS farm_plots_farmer_idx ON farm_plots (farmer_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS harvest_records (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  plot_id       uuid REFERENCES farm_plots(id) ON DELETE SET NULL,
  crop_type     text NOT NULL CHECK (char_length(btrim(crop_type)) BETWEEN 1 AND 80),
  quantity_kg   numeric(14,2) NOT NULL CHECK (quantity_kg >= 0),
  harvest_date  date NOT NULL,
  notes         text,
  client_id     text,
  created_by    uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS harvest_client_unique ON harvest_records (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS harvest_farmer_date_idx ON harvest_records (farmer_id, harvest_date DESC);

CREATE TABLE IF NOT EXISTS livestock_counts (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  animal_type   text NOT NULL CHECK (char_length(btrim(animal_type)) BETWEEN 1 AND 80),
  head_count    integer NOT NULL CHECK (head_count >= 0),
  record_date   date NOT NULL,
  notes         text,
  client_id     text,
  created_by    uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS livestock_client_unique ON livestock_counts (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS livestock_farmer_date_idx ON livestock_counts (farmer_id, record_date DESC);

CREATE TABLE IF NOT EXISTS sale_transactions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  item          text NOT NULL CHECK (char_length(btrim(item)) BETWEEN 1 AND 120),
  quantity      numeric(14,2) NOT NULL CHECK (quantity >= 0),
  unit_price    numeric(14,2) CHECK (unit_price IS NULL OR unit_price >= 0),
  amount        numeric(14,2) NOT NULL CHECK (amount >= 0),
  currency      char(3) NOT NULL DEFAULT 'BWP',
  buyer_name    text,
  sale_date     date NOT NULL,
  notes         text,
  client_id     text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS sale_client_unique ON sale_transactions (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS sale_farmer_date_idx ON sale_transactions (farmer_id, sale_date DESC);

CREATE TABLE IF NOT EXISTS expenses (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  category      text NOT NULL CHECK (char_length(btrim(category)) BETWEEN 1 AND 80),
  description   text,
  amount        numeric(14,2) NOT NULL CHECK (amount >= 0),
  currency      char(3) NOT NULL DEFAULT 'BWP',
  expense_date  date NOT NULL,
  client_id     text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS expense_client_unique ON expenses (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS expense_farmer_date_idx ON expenses (farmer_id, expense_date DESC);

CREATE TABLE IF NOT EXISTS satellite_ndvi (
  id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plot_id                uuid NOT NULL REFERENCES farm_plots(id) ON DELETE CASCADE,
  farmer_id              uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  satellite_date         date,
  mean_ndvi_value        numeric(5,4) CHECK (mean_ndvi_value IS NULL OR (mean_ndvi_value >= -1 AND mean_ndvi_value <= 1)),
  stress_level           text CHECK (stress_level IS NULL OR stress_level IN ('HIGH_STRESS','MODERATE_STRESS','HEALTHY_VEGETATION')),
  status                 text NOT NULL DEFAULT 'REQUESTED'
                           CHECK (status IN ('REQUESTED','FETCHING_IMAGERY','COMPUTING_NDVI','GENERATED','FAILED')),
  failure_reason         text,
  cloud_cover            numeric(5,2),
  compressed_vector_json jsonb,
  is_cached              boolean NOT NULL DEFAULT false,
  source                 text NOT NULL DEFAULT 'SENTINEL-2',
  created_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS satellite_plot_idx  ON satellite_ndvi (plot_id, satellite_date DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS satellite_farmer_idx ON satellite_ndvi (farmer_id);

-- ---------------------------------------------------------------------------
-- 4c. 003_marketplace — listings and orders
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS produce_listings (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  produce         text NOT NULL CHECK (char_length(btrim(produce)) BETWEEN 2 AND 120),
  description     text,
  quantity        numeric(14,2) NOT NULL CHECK (quantity >= 0),
  reserved_qty    numeric(14,2) NOT NULL DEFAULT 0 CHECK (reserved_qty >= 0),
  sold_qty        numeric(14,2) NOT NULL DEFAULT 0 CHECK (sold_qty >= 0),
  unit            text NOT NULL DEFAULT 'kg' CHECK (unit IN ('kg','tonne','bag','crate','bunch','head','litre')),
  price           numeric(14,2) NOT NULL CHECK (price >= 0),
  currency        char(3) NOT NULL DEFAULT 'BWP',
  status          listing_status NOT NULL DEFAULT 'DRAFT',
  image_url       text,
  location_text   text,
  published_at    timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  deleted_at      timestamptz,
  CONSTRAINT listing_stock_sane CHECK (reserved_qty + sold_qty <= quantity)
);
CREATE INDEX IF NOT EXISTS listings_status_idx  ON produce_listings (status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS listings_farmer_idx  ON produce_listings (farmer_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS listings_produce_idx ON produce_listings (lower(produce));

CREATE TABLE IF NOT EXISTS orders (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id        uuid NOT NULL REFERENCES produce_listings(id) ON DELETE RESTRICT,
  buyer_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  farmer_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  quantity          numeric(14,2) NOT NULL CHECK (quantity > 0),
  unit_price        numeric(14,2) NOT NULL CHECK (unit_price >= 0),
  total_price       numeric(14,2) NOT NULL CHECK (total_price >= 0),
  currency          char(3) NOT NULL DEFAULT 'BWP',
  status            order_status NOT NULL DEFAULT 'PENDING',
  note              text,
  buyer_contact     text,
  farmer_decision_at timestamptz,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS orders_buyer_idx   ON orders (buyer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS orders_farmer_idx  ON orders (farmer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS orders_listing_idx ON orders (listing_id);
CREATE INDEX IF NOT EXISTS orders_status_idx  ON orders (status);

-- ---------------------------------------------------------------------------
-- 4d. 004_sync_notifications_retention — feed, sync ledger, retention, audit
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notifications (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type                text NOT NULL,
  title               text NOT NULL,
  body                text NOT NULL,
  related_entity_type text,
  related_entity_id   uuid,
  is_read             boolean NOT NULL DEFAULT false,
  created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS notifications_user_idx  ON notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS notifications_unread_idx ON notifications (user_id) WHERE is_read = false;

CREATE TABLE IF NOT EXISTS device_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token       text NOT NULL,
  platform    text NOT NULL DEFAULT 'android',
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, token)
);

CREATE TABLE IF NOT EXISTS sync_queue_items (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  queue_id         text NOT NULL,
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
CREATE INDEX IF NOT EXISTS sync_queue_user_status_idx ON sync_queue_items (user_id, status);
CREATE INDEX IF NOT EXISTS sync_queue_entity_idx      ON sync_queue_items (user_id, entity_type, entity_id);

CREATE TABLE IF NOT EXISTS account_deletion_requests (
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
CREATE INDEX IF NOT EXISTS deletion_pending_idx ON account_deletion_requests (status, effective_at);
CREATE UNIQUE INDEX IF NOT EXISTS deletion_one_active_per_user ON account_deletion_requests (user_id)
  WHERE status IN ('PENDING','PROCESSING');

CREATE TABLE IF NOT EXISTS retention_ledger (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  subject_user   uuid NOT NULL,
  data_category  text NOT NULL,
  justification  text NOT NULL,
  retain_until   date NOT NULL,
  pseudonym      text NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS retention_subject_idx ON retention_ledger (subject_user);

CREATE TABLE IF NOT EXISTS audit_log (
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
CREATE INDEX IF NOT EXISTS audit_actor_idx ON audit_log (actor_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS audit_action_idx ON audit_log (action, created_at DESC);

CREATE TABLE IF NOT EXISTS message_outbox (
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
CREATE INDEX IF NOT EXISTS message_outbox_dest_idx ON message_outbox (destination, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4e. 005_employee_link — link a FARM_EMPLOYEE to exactly one FARMER
-- ---------------------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS employer_farmer_id uuid REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS users_employer_idx ON users (employer_farmer_id) WHERE employer_farmer_id IS NOT NULL;

CREATE OR REPLACE FUNCTION enforce_employer_link() RETURNS trigger AS $fn$
DECLARE
  employer_role user_role;
BEGIN
  IF NEW.employer_farmer_id IS NULL THEN
    RETURN NEW;
  END IF;
  IF NEW.employer_farmer_id = NEW.id THEN
    RAISE EXCEPTION 'A user cannot be their own employer';
  END IF;
  SELECT role INTO employer_role FROM users WHERE id = NEW.employer_farmer_id;
  IF employer_role IS NULL THEN
    RAISE EXCEPTION 'Employer does not exist';
  END IF;
  IF employer_role <> 'FARMER' THEN
    RAISE EXCEPTION 'Only a FARMER can be an employer';
  END IF;
  RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 4f. plot photos — /uploads path of a farm-plot photo (optional)
-- ---------------------------------------------------------------------------
ALTER TABLE farm_plots ADD COLUMN IF NOT EXISTS image_url text;

-- ---------------------------------------------------------------------------
-- 5. Triggers (CREATE TRIGGER has no IF NOT EXISTS → drop then recreate)
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS users_set_updated_at ON users;
CREATE TRIGGER users_set_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS farm_plots_set_updated_at ON farm_plots;
CREATE TRIGGER farm_plots_set_updated_at BEFORE UPDATE ON farm_plots
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS harvest_set_updated_at ON harvest_records;
CREATE TRIGGER harvest_set_updated_at BEFORE UPDATE ON harvest_records
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS livestock_set_updated_at ON livestock_counts;
CREATE TRIGGER livestock_set_updated_at BEFORE UPDATE ON livestock_counts
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS sale_set_updated_at ON sale_transactions;
CREATE TRIGGER sale_set_updated_at BEFORE UPDATE ON sale_transactions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS expense_set_updated_at ON expenses;
CREATE TRIGGER expense_set_updated_at BEFORE UPDATE ON expenses
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS listings_set_updated_at ON produce_listings;
CREATE TRIGGER listings_set_updated_at BEFORE UPDATE ON produce_listings
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS orders_set_updated_at ON orders;
CREATE TRIGGER orders_set_updated_at BEFORE UPDATE ON orders
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS users_enforce_employer ON users;
CREATE TRIGGER users_enforce_employer BEFORE INSERT OR UPDATE OF employer_farmer_id ON users
  FOR EACH ROW EXECUTE FUNCTION enforce_employer_link();

-- ---------------------------------------------------------------------------
-- 6. Migration bookkeeping — must match src/db/migrate.js
--    Recording the filenames here means a later `npm run migrate` will skip
--    these files rather than fail with "already exists".
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_migrations (
  filename    text PRIMARY KEY,
  applied_at  timestamptz NOT NULL DEFAULT now(),
  checksum    text
);

INSERT INTO schema_migrations (filename) VALUES
  ('001_init.sql'),
  ('002_domain.sql'),
  ('003_marketplace.sql'),
  ('004_sync_notifications_retention.sql'),
  ('005_employee_link.sql')
ON CONFLICT (filename) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7. Row Level Security
--
--    The Express API is the only client: it connects as `postgres`, which owns
--    these tables, and RLS never applies to a table's owner (unless FORCE is
--    set — deliberately NOT set here, or the API itself would be locked out).
--    Enabling RLS with no policies therefore blocks Supabase's PostgREST/anon
--    key from reading any table, while leaving the backend untouched.
--    Skip this block only if you deliberately serve these tables over REST.
-- ---------------------------------------------------------------------------
DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'users','refresh_tokens','verification_tokens','idempotency_keys',
    'farm_plots','harvest_records','livestock_counts','sale_transactions',
    'expenses','satellite_ndvi','produce_listings','orders',
    'notifications','device_tokens','sync_queue_items',
    'account_deletion_requests','retention_ledger','audit_log','message_outbox',
    'schema_migrations'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
  END LOOP;
END
$rls$;

COMMIT;

-- ============================================================================
-- VERIFICATION — run this on its own. "missing_tables" must return no rows.
-- ============================================================================
SELECT
  expected.table_name,
  (expected.table_name IS NOT NULL AND actual.table_name IS NULL) AS is_missing,
  actual.table_name IS NOT NULL AS exists_now
FROM (
  SELECT unnest(ARRAY[
    'users','refresh_tokens','verification_tokens','idempotency_keys',
    'farm_plots','harvest_records','livestock_counts','sale_transactions',
    'expenses','satellite_ndvi','produce_listings','orders',
    'notifications','device_tokens','sync_queue_items',
    'account_deletion_requests','retention_ledger','audit_log','message_outbox',
    'schema_migrations'
  ]) AS table_name
) expected
LEFT JOIN information_schema.tables actual
  ON actual.table_schema = 'public'
 AND actual.table_name = expected.table_name
  AND actual.table_type = 'BASE TABLE'
ORDER BY exists_now, table_name;

-- Migration bookkeeping should list all five files:
SELECT filename, applied_at FROM schema_migrations ORDER BY filename;

-- Which columns did 005 add? Should return exactly one row.
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'employer_farmer_id';
