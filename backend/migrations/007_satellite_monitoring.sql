-- ============================================================================
-- AgriConnect - 007_satellite_monitoring.sql
--
-- Satellite monitoring: a per-plot observation cache, the farmer event log and
-- the derived alerts.
--
-- Why a new table rather than columns on `satellite_ndvi`: `satellite_ndvi`
-- records *requests* (including FAILED ones, which is deliberate), whereas
-- `plot_observations` records a *clean time series* - one row per plot and
-- acquisition date, with the valid-pixel coverage. Keeping them apart means a
-- failed request never pollutes the series and a reprocessed series never
-- destroys the request audit trail.
--
-- All three tables are additive; nothing existing is altered or dropped.
--
-- Safe to run more than once: every statement is guarded, and the triggers are
-- dropped before being recreated (PostgreSQL has no CREATE TRIGGER IF NOT
-- EXISTS). That matters because this file can be applied either by
-- `npm run migrate` or by pasting it into the Supabase SQL editor, and the two
-- paths must not conflict.
-- ============================================================================
BEGIN;

-- --------------------------------------------------------------------------
-- 1. Clean observation series (cache of what the processor returned)
-- --------------------------------------------------------------------------
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

DROP TRIGGER IF EXISTS plot_observations_set_updated_at ON plot_observations;
CREATE TRIGGER plot_observations_set_updated_at BEFORE UPDATE ON plot_observations
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- --------------------------------------------------------------------------
-- 2. Farmer event log (detected events + the farmer's own records)
-- --------------------------------------------------------------------------
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
  -- a kind here - any such explanation lives in `classification`.
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
  -- One detected event per (plot, date, classification): re-running the analysis
  -- updates the existing suggestion instead of adding a duplicate.
  CONSTRAINT plot_events_detected_key_unique UNIQUE (plot_id, event_key)
);

CREATE INDEX IF NOT EXISTS idx_plot_events_plot_date
  ON plot_events (plot_id, event_date DESC);
CREATE INDEX IF NOT EXISTS idx_plot_events_farmer
  ON plot_events (farmer_id, created_at DESC);
-- The offline queue replays the same client_id; this is what makes that safe.
CREATE UNIQUE INDEX IF NOT EXISTS idx_plot_events_client_unique
  ON plot_events (plot_id, client_id) WHERE client_id IS NOT NULL;

DROP TRIGGER IF EXISTS plot_events_set_updated_at ON plot_events;
CREATE TRIGGER plot_events_set_updated_at BEFORE UPDATE ON plot_events
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- --------------------------------------------------------------------------
-- 3. Alerts (deduplicated by alert_key, acknowledgeable without losing history)
-- --------------------------------------------------------------------------
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

DROP TRIGGER IF EXISTS plot_alerts_set_updated_at ON plot_alerts;
CREATE TRIGGER plot_alerts_set_updated_at BEFORE UPDATE ON plot_alerts
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- --------------------------------------------------------------------------
-- 4. Verified-label counts, for honest model-status reporting
-- --------------------------------------------------------------------------
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

-- --------------------------------------------------------------------------
-- 5. Row Level Security
-- --------------------------------------------------------------------------
-- Same hardening as the other tables (see supabase-update.sql section 7): the
-- API connects as the table owner, and RLS never applies to an owner unless
-- FORCE is set (deliberately NOT set). Enabling RLS with no policies therefore
-- blocks Supabase's PostgREST/anon key from reading farmer satellite data while
-- leaving the backend untouched.
DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['plot_observations', 'plot_events', 'plot_alerts'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
  END LOOP;
END
$rls$;

COMMIT;
