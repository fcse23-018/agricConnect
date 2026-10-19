-- 007_satellite_monitoring.sql
--
-- Satellite monitoring: a per-plot observation cache, the farmer event log and
-- the derived alerts.
--
-- Why a new table rather than columns on `satellite_ndvi`: `satellite_ndvi`
-- records *requests* (including FAILED ones, which is deliberate), whereas
-- `plot_observations` records a *clean time series* — one row per plot and
-- acquisition date, with the valid-pixel coverage. Keeping them apart means a
-- failed request never pollutes the series and a reprocessed series never
-- destroys the request audit trail.
--
-- All three tables are additive; nothing existing is altered or dropped.

-- --------------------------------------------------------------------------
-- 1. Clean observation series (cache of what the processor returned)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plot_observations (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plot_id        uuid NOT NULL REFERENCES farm_plots(id) ON DELETE CASCADE,
  farmer_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  observed_on    date NOT NULL,
  ndvi           numeric(8, 4),
  evi            numeric(8, 4),
  valid_pixel_pct numeric(6, 2),
  cloud_cover_pct numeric(6, 2),
  pixel_count    integer,
  quality        text NOT NULL DEFAULT 'GOOD'
                   CHECK (quality IN ('GOOD', 'FAIR', 'POOR')),
  is_simulated   boolean NOT NULL DEFAULT false,
  source         text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plot_observations_unique UNIQUE (plot_id, observed_on)
);

CREATE INDEX IF NOT EXISTS idx_plot_observations_plot_date
  ON plot_observations (plot_id, observed_on DESC);

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
  kind              text NOT NULL
                      CHECK (kind IN ('DETECTED_DROP', 'HARVEST', 'CLEARING', 'HEAT_STRESS',
                                      'WATER_STRESS', 'OBSERVATION', 'OTHER')),
  source            text NOT NULL DEFAULT 'FARMER'
                      CHECK (source IN ('FARMER', 'SYSTEM')),
  classification    text,
  confirmed         boolean NOT NULL DEFAULT false,
  notes             text,
  ndvi_before       numeric(8, 4),
  ndvi_after        numeric(8, 4),
  magnitude         numeric(8, 4),
  confidence        text,
  evidence          jsonb,
  weather_context   jsonb,
  event_key         text,
  client_id         text,
  recorded_by       uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT plot_events_detected_key_unique UNIQUE (plot_id, event_key)
);

CREATE INDEX IF NOT EXISTS idx_plot_events_plot_date
  ON plot_events (plot_id, event_date DESC);
CREATE INDEX IF NOT EXISTS idx_plot_events_farmer
  ON plot_events (farmer_id, created_at DESC);

-- --------------------------------------------------------------------------
-- 3. Alerts (deduplicated by alert_key, acknowledgeable without losing history)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plot_alerts (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  plot_id      uuid NOT NULL REFERENCES farm_plots(id) ON DELETE CASCADE,
  farmer_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
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

-- --------------------------------------------------------------------------
-- 4. Record the farmer's own event log as label data for future model work
-- --------------------------------------------------------------------------
-- Nothing is trained here. This view simply counts verified farmer events per
-- plot so the API can report honestly whether enough labels exist yet.
CREATE OR REPLACE VIEW plot_event_label_counts AS
  SELECT plot_id,
         COUNT(*) FILTER (WHERE source = 'FARMER' AND kind IN ('HARVEST', 'CLEARING'))::int AS harvest_or_clearing_labels,
         COUNT(*) FILTER (WHERE source = 'FARMER')::int AS total_farmer_labels
    FROM plot_events
   GROUP BY plot_id;
