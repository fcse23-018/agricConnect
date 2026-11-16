-- ============================================================================
-- AgriConnect · Supabase schema update (idempotent)
-- ============================================================================
--
-- WHAT THIS IS
--   A single, re-runnable script that brings a Supabase PostgreSQL database up
--   to the state described by backend/migrations/001..009. It is safe to run on
--   a database in ANY state:
--     * empty database        → creates the whole schema
--     * fully migrated        → no-ops (every statement is guarded)
--     * partially migrated    → applies only what is missing
--   The whole script runs in ONE transaction: if any statement fails, nothing
--   is committed. Migrations 007..009 each define their own BEGIN/COMMIT; those
--   wrappers are deliberately omitted below so the sections join this script's
--   single transaction instead of nesting a transaction inside it.
--
--   Migrations 007..009 contribute, and this script therefore also creates:
--     * 007  plot_observations, plot_events, plot_alerts and the
--            plot_event_label_counts view
--     * 008  the widened produce_listings unit CHECK (adds 'herd' and 'flock')
--     * 009  herd_events, the herd_register_summary view, and the per-animal
--            listing columns animal_class, age_months, weight_kg, tag_number
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
--   All nine migration filenames are recorded in schema_migrations, so a later
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
      ('user_role',       'ENUM (''FARM_OWNER'',''FARMER'',''BUYER'')'),
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
  role                    user_role NOT NULL DEFAULT 'FARM_OWNER',
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
-- 4e. 005_employee_link — link a FARMER to exactly one employing FARM_OWNER
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
  IF employer_role <> 'FARM_OWNER' THEN
    RAISE EXCEPTION 'Only a farm owner can be an employer';
  END IF;
  RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- 4f. plot photos — /uploads path of a farm-plot photo (optional)
-- ---------------------------------------------------------------------------
ALTER TABLE farm_plots ADD COLUMN IF NOT EXISTS image_url text;

-- ---------------------------------------------------------------------------
-- 4g. 006_role_rename — display-accurate role labels
--
--     FARMER (owns the farm)        → FARM_OWNER
--     FARM_EMPLOYEE (works the land) → FARMER
--
--     In-place rename: no row is touched, so every account keeps its capabilities
--     and only its label changes. Guarded so it is a no-op when the labels are
--     already new (fresh database built by section 2) and when they are old
--     (an existing database). Section 4e's function was already written against
--     FARM_OWNER above.
-- ---------------------------------------------------------------------------
DO $roles$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
     WHERE t.typname = 'user_role' AND e.enumlabel = 'FARMER'
  ) AND EXISTS (
    SELECT 1 FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
     WHERE t.typname = 'user_role' AND e.enumlabel = 'FARM_EMPLOYEE'
  ) THEN
    ALTER TYPE user_role RENAME VALUE 'FARMER' TO 'FARM_OWNER';
    ALTER TYPE user_role RENAME VALUE 'FARM_EMPLOYEE' TO 'FARMER';
    RAISE NOTICE 'user_role renamed: FARMER -> FARM_OWNER, FARM_EMPLOYEE -> FARMER';
  END IF;
END
$roles$;

ALTER TABLE users ALTER COLUMN role SET DEFAULT 'FARM_OWNER';

-- ---------------------------------------------------------------------------
-- 4h. 007_satellite_monitoring — clean observation series, farmer event log and
--     derived alerts
--
--     Why new tables rather than columns on `satellite_ndvi`: `satellite_ndvi`
--     records *requests* (including FAILED ones, which is deliberate), whereas
--     `plot_observations` records a *clean time series* — one row per plot and
--     acquisition date, with the valid-pixel coverage. A failed request never
--     pollutes the series and a reprocessed series never destroys the request
--     audit trail. All three tables are additive; nothing existing is altered.
--     Triggers are in section 5 and RLS in section 7, matching the layout above.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plot_observations (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plot_id        uuid NOT NULL REFERENCES farm_plots(id) ON DELETE CASCADE,
  farmer_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  observed_on    date NOT NULL,
  ndvi           numeric(8, 4) CHECK (ndvi IS NULL OR (ndvi >= -1 AND ndvi <= 1)),
  evi            numeric(8, 4) CHECK (evi IS NULL OR (evi >= -1 AND evi <= 1)),
  valid_pixel_pct numeric(6, 2) CHECK (valid_pixel_pct IS NULL OR (valid_pixel_pct >= 0 AND valid_pixel_pct <= 100)),
  cloud_cover_pct numeric(6, 2) CHECK (cloud_cover_pct IS NULL OR (cloud_cover_pct >= 0 AND cloud_cover_pct <= 100)),
  pixel_count    integer CHECK (pixel_count IS NULL OR pixel_count >= 0),
  quality        text NOT NULL DEFAULT 'GOOD'
                   CHECK (quality IN ('GOOD', 'FAIR', 'POOR')),
  -- True when the reading came from the deterministic generator, not Sentinel-2.
  -- A row is never downgraded from real to simulated, only the other way round.
  is_simulated   boolean NOT NULL DEFAULT false,
  source         text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plot_observations_unique UNIQUE (plot_id, observed_on)
);

CREATE INDEX IF NOT EXISTS idx_plot_observations_plot_date
  ON plot_observations (plot_id, observed_on DESC);
CREATE INDEX IF NOT EXISTS idx_plot_observations_farmer
  ON plot_observations (farmer_id, observed_on DESC);

-- `kind` is what happened; `classification` is what the system thinks it was.
-- A farmer-created row (source = 'FARMER') is the training label; a system row
-- is a suggestion the farmer may confirm or correct.
CREATE TABLE IF NOT EXISTS plot_events (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plot_id           uuid NOT NULL REFERENCES farm_plots(id) ON DELETE CASCADE,
  farmer_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_date        date NOT NULL,
  -- DETECTED_DROP is only ever written by the analysis engine; the rest may be
  -- written by the farmer. The engine never claims a cause, so POSSIBLE_* is not
  -- a kind here — any such explanation lives in `classification`.
  kind              text NOT NULL
                      CHECK (kind IN ('DETECTED_DROP', 'HARVEST', 'CLEARING', 'HEAT_STRESS',
                                      'WATER_STRESS', 'OBSERVATION', 'OTHER')),
  source            text NOT NULL DEFAULT 'FARMER'
                      CHECK (source IN ('FARMER', 'SYSTEM')),
  classification    text,
  -- Only a confirmed row may ever be used as a training label.
  confirmed         boolean NOT NULL DEFAULT false,
  notes             text,
  ndvi_before       numeric(8, 4),
  ndvi_after        numeric(8, 4),
  magnitude         numeric(8, 4),
  confidence        text CHECK (confidence IS NULL OR confidence IN ('HIGH', 'MEDIUM', 'LOW')),
  evidence          jsonb,
  weather_context   jsonb,
  event_key         text,
  client_id         text,
  recorded_by       uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  -- One detected event per (plot, event_key): re-running the analysis updates
  -- the existing suggestion instead of adding a duplicate.
  CONSTRAINT plot_events_detected_key_unique UNIQUE (plot_id, event_key)
);

CREATE INDEX IF NOT EXISTS idx_plot_events_plot_date
  ON plot_events (plot_id, event_date DESC);
CREATE INDEX IF NOT EXISTS idx_plot_events_farmer
  ON plot_events (farmer_id, created_at DESC);
-- The offline queue replays the same client_id; this is what makes that safe.
CREATE UNIQUE INDEX IF NOT EXISTS idx_plot_events_client_unique
  ON plot_events (plot_id, client_id) WHERE client_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS plot_alerts (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plot_id      uuid NOT NULL REFERENCES farm_plots(id) ON DELETE CASCADE,
  farmer_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- Stable for the same ongoing event, e.g. 'NDVI_DECLINE:2026-03-11'. This is
  -- what stops one ongoing drop from raising a new alert on every analysis run.
  alert_key    text NOT NULL,
  alert_type   text NOT NULL,
  severity     text NOT NULL DEFAULT 'INFO'
                 CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  title        text NOT NULL,
  body         text NOT NULL,
  status       text NOT NULL DEFAULT 'OPEN'
                 CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'DISMISSED')),
  raised_at    timestamptz NOT NULL DEFAULT now(),
  resolved_at  timestamptz,
  acknowledged_at timestamptz,
  dismissed_at timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plot_alerts_key_unique UNIQUE (plot_id, alert_key)
);

CREATE INDEX IF NOT EXISTS idx_plot_alerts_plot_status
  ON plot_alerts (plot_id, status, raised_at DESC);
CREATE INDEX IF NOT EXISTS idx_plot_alerts_farmer
  ON plot_alerts (farmer_id, status, raised_at DESC);

-- Nothing is trained here. This view simply counts *confirmed* farmer events per
-- plot so the API can report truthfully whether enough labels exist yet. An
-- unconfirmed system suggestion is deliberately not counted: it would be
-- training on the model's own guesses.
CREATE OR REPLACE VIEW plot_event_label_counts AS
  SELECT plot_id,
         COUNT(*) FILTER (WHERE source = 'FARMER' AND confirmed
                            AND kind IN ('HARVEST', 'CLEARING'))::int AS harvest_or_clearing_labels,
         COUNT(*) FILTER (WHERE source = 'FARMER' AND confirmed)::int AS total_farmer_labels
    FROM plot_events
   GROUP BY plot_id;

-- ---------------------------------------------------------------------------
-- 4i. 008_livestock_units — allow livestock to be sold as a whole group
--
--     Farmers sell animals two ways: individually ("20 head of cattle") and as
--     one lot ("the herd"). `head` already existed; this adds `herd` (cattle,
--     goats, donkeys — any mixed/herd animal) and `flock` (sheep, goats, poultry
--     — animals kept as a flock). The list must stay in step with LISTING_UNITS
--     in src/modules/marketplace/marketplace.schemas.js.
--
--     The original constraint in 003_marketplace.sql was defined inline, so
--     PostgreSQL generated its name. Find and drop it by whatever name it
--     actually has, rather than assuming `produce_listings_unit_check`. It is
--     re-added under a stable name, guarded so a re-run is a no-op.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
  conname_to_drop text;
BEGIN
  FOR conname_to_drop IN
    SELECT con.conname
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'produce_listings'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) ILIKE '%unit%'
      AND pg_get_constraintdef(con.oid) ILIKE '%crate%'
  LOOP
    EXECUTE format('ALTER TABLE produce_listings DROP CONSTRAINT %I', conname_to_drop);
  END LOOP;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
     WHERE conname = 'produce_listings_unit_check'
       AND conrelid = 'produce_listings'::regclass
  ) THEN
    ALTER TABLE produce_listings
      ADD CONSTRAINT produce_listings_unit_check
      CHECK (unit IN ('kg','tonne','bag','crate','bunch','head','herd','flock','litre'));
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4j. 009_herd_register — herd register (CR1) and per-animal listing
--     attributes (CR2)
--
--     `herd_events` records births, deaths, weighings, sales and dipping /
--     veterinary dates per animal type, with the offline-first `client_id` key
--     every other farm table uses. `herd_register_summary` computes the
--     automatic totals so no code has to keep the sums in step by hand.
--
--     `produce_listings` gains `animal_class`, `age_months`, `weight_kg` and
--     `tag_number`. All four are nullable: a sack of maize has none of them, and
--     a listing must never force a farmer to invent data. ADD COLUMN IF NOT
--     EXISTS skips the whole column definition (including its inline CHECK)
--     when the column already exists, so a re-run adds nothing.
-- ---------------------------------------------------------------------------
ALTER TABLE produce_listings
  ADD COLUMN IF NOT EXISTS animal_class text
    CHECK (animal_class IS NULL OR char_length(btrim(animal_class)) BETWEEN 1 AND 60),
  ADD COLUMN IF NOT EXISTS age_months integer
    CHECK (age_months IS NULL OR age_months BETWEEN 0 AND 600),
  ADD COLUMN IF NOT EXISTS weight_kg numeric(10,2)
    CHECK (weight_kg IS NULL OR weight_kg >= 0),
  ADD COLUMN IF NOT EXISTS tag_number text
    CHECK (tag_number IS NULL OR char_length(btrim(tag_number)) BETWEEN 1 AND 60);

-- A tag is how a keeper looks up one animal, so support "show me everything
-- about ear tag BW0123" without a scan of every listing the farmer ever made.
CREATE INDEX IF NOT EXISTS listings_tag_idx
  ON produce_listings (farmer_id, tag_number) WHERE deleted_at IS NULL AND tag_number IS NOT NULL;

-- One row is one register entry. `head_count` is how many animals the entry
-- concerns (3 calves born; 1 tagged animal weighed), so the summary view can
-- add births and deaths without a second table. The money columns follow
-- `sale_transactions`: `unit_price` is per animal, `amount` the entry total.
-- Both are nullable — a dipping date has neither.
CREATE TABLE IF NOT EXISTS herd_events (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  animal_type   text NOT NULL CHECK (char_length(btrim(animal_type)) BETWEEN 1 AND 80),
  event_type    text NOT NULL
                  CHECK (event_type IN ('BIRTH', 'DEATH', 'SALE', 'WEIGHING', 'DIPPING', 'VETERINARY', 'OTHER')),
  event_date    date NOT NULL,
  head_count    integer NOT NULL DEFAULT 1 CHECK (head_count >= 1),
  weight_kg     numeric(10,2) CHECK (weight_kg IS NULL OR weight_kg >= 0),
  unit_price    numeric(14,2) CHECK (unit_price IS NULL OR unit_price >= 0),
  amount        numeric(14,2) CHECK (amount IS NULL OR amount >= 0),
  tag_number    text CHECK (tag_number IS NULL OR char_length(btrim(tag_number)) BETWEEN 1 AND 60),
  notes         text,
  client_id     text,
  created_by    uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  deleted_at    timestamptz
);

-- The offline queue replays the same client_id; this is what makes that safe.
-- Partial index: NULL client_id (an online create) must stay unlimited.
CREATE UNIQUE INDEX IF NOT EXISTS herd_client_unique
  ON herd_events (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS herd_farmer_date_idx
  ON herd_events (farmer_id, event_date DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS herd_tag_idx
  ON herd_events (farmer_id, tag_number) WHERE deleted_at IS NULL AND tag_number IS NOT NULL;

-- The register's headline numbers, per animal type, computed rather than stored
-- so they can never disagree with the entries. Soft-deleted rows are excluded:
-- a corrected entry disappears from the totals the moment it is deleted. Animal
-- types with no surviving entries are simply absent.
CREATE OR REPLACE VIEW herd_register_summary AS
  SELECT farmer_id,
         animal_type,
         COALESCE(SUM(head_count) FILTER (WHERE event_type = 'BIRTH'), 0)::int AS births,
         COALESCE(SUM(head_count) FILTER (WHERE event_type = 'DEATH'), 0)::int AS deaths,
         COALESCE(SUM(head_count) FILTER (WHERE event_type = 'SALE'), 0)::int AS head_sold,
         COALESCE(SUM(amount) FILTER (WHERE event_type = 'SALE'), 0)::numeric AS sale_proceeds,
         COUNT(*) FILTER (WHERE event_type = 'WEIGHING')::int AS weighing_entries,
         MAX(event_date) FILTER (WHERE event_type = 'WEIGHING') AS last_weighed_on,
         MAX(event_date) FILTER (WHERE event_type = 'DIPPING') AS last_dipped_on,
         MAX(event_date) FILTER (WHERE event_type = 'VETERINARY') AS last_veterinary_on,
         MAX(event_date) AS last_event_on,
         COUNT(*)::int AS entries
    FROM herd_events
   WHERE deleted_at IS NULL
   GROUP BY farmer_id, animal_type;

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

-- 007 / 009 tables (their migration files define the same triggers inline)
DROP TRIGGER IF EXISTS plot_observations_set_updated_at ON plot_observations;
CREATE TRIGGER plot_observations_set_updated_at BEFORE UPDATE ON plot_observations
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS plot_events_set_updated_at ON plot_events;
CREATE TRIGGER plot_events_set_updated_at BEFORE UPDATE ON plot_events
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS plot_alerts_set_updated_at ON plot_alerts;
CREATE TRIGGER plot_alerts_set_updated_at BEFORE UPDATE ON plot_alerts
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS herd_events_set_updated_at ON herd_events;
CREATE TRIGGER herd_events_set_updated_at BEFORE UPDATE ON herd_events
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
  ('005_employee_link.sql'),
  ('006_role_rename.sql'),
  ('007_satellite_monitoring.sql'),
  ('008_livestock_units.sql'),
  ('009_herd_register.sql')
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
    'plot_observations','plot_events','plot_alerts','herd_events',
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
    'plot_observations','plot_events','plot_alerts','herd_events',
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

-- Migration bookkeeping should list all nine files:
SELECT filename, applied_at FROM schema_migrations ORDER BY filename;

-- The role labels should read FARM_OWNER, FARMER, BUYER — in that order.
SELECT enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
 WHERE t.typname = 'user_role' ORDER BY e.enumsortorder;

-- Which columns did 005 add? Should return exactly one row.
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'employer_farmer_id';

-- 007: the observation series, farmer event log, alerts and label-count view.
-- Expect four rows ('r' = table, 'v' = view).
SELECT c.relname AS object_name, c.relkind
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relname IN ('plot_observations', 'plot_events', 'plot_alerts', 'plot_event_label_counts')
ORDER BY c.relkind, c.relname;

-- 007/009: the updated_at triggers on the new tables. Expect four rows.
SELECT event_object_table AS table_name, trigger_name
FROM information_schema.triggers
WHERE trigger_schema = 'public'
  AND trigger_name IN ('plot_observations_set_updated_at', 'plot_events_set_updated_at',
                       'plot_alerts_set_updated_at', 'herd_events_set_updated_at')
ORDER BY table_name;

-- 008: the widened unit rule must list herd and flock. Expect one row.
SELECT conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'produce_listings'::regclass
  AND conname = 'produce_listings_unit_check';

-- 009: the herd register table and its summary view. Expect two rows.
SELECT table_name, table_type
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN ('herd_events', 'herd_register_summary')
ORDER BY table_name;

-- 009: the per-animal listing attributes. Expect exactly four rows.
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'produce_listings'
  AND column_name IN ('animal_class', 'age_months', 'weight_kg', 'tag_number')
ORDER BY column_name;

-- 009: the herd register columns, in table order.
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'herd_events'
ORDER BY ordinal_position;
