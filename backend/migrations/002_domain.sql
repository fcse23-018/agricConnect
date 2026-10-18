-- ============================================================================
-- AgriConnect · 002_domain.sql
-- Farm plots, harvests, livestock, finance, satellite NDVI, marketplace, orders.
-- ============================================================================
BEGIN;

-- ---------------------------------------------------------------------------
-- farm_plots   (GeoJSON boundary; ownership: farmer_id)
-- ---------------------------------------------------------------------------
CREATE TABLE farm_plots (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  farmer_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id        text,                    -- device-generated id (offline-first)
  plot_name        text NOT NULL CHECK (char_length(btrim(plot_name)) BETWEEN 1 AND 120),
  area_hectares    numeric(12,4) CHECK (area_hectares IS NULL OR area_hectares >= 0),
  geojson_boundary jsonb,                   -- GeoJSON Polygon
  centroid_lat     double precision,
  centroid_lng     double precision,
  notes            text,
  image_url        text,                    -- /uploads path of a plot photo
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);
CREATE UNIQUE INDEX farm_plots_client_unique ON farm_plots (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX farm_plots_farmer_idx ON farm_plots (farmer_id) WHERE deleted_at IS NULL;
CREATE TRIGGER farm_plots_set_updated_at BEFORE UPDATE ON farm_plots
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- harvest_records            (farmer + employee may write)
-- ---------------------------------------------------------------------------
CREATE TABLE harvest_records (
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
CREATE UNIQUE INDEX harvest_client_unique ON harvest_records (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX harvest_farmer_date_idx ON harvest_records (farmer_id, harvest_date DESC);
CREATE TRIGGER harvest_set_updated_at BEFORE UPDATE ON harvest_records
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- livestock_counts           (farmer + employee may write)
-- ---------------------------------------------------------------------------
CREATE TABLE livestock_counts (
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
CREATE UNIQUE INDEX livestock_client_unique ON livestock_counts (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX livestock_farmer_date_idx ON livestock_counts (farmer_id, record_date DESC);
CREATE TRIGGER livestock_set_updated_at BEFORE UPDATE ON livestock_counts
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- sale_transactions          (FARMER ONLY)
-- ---------------------------------------------------------------------------
CREATE TABLE sale_transactions (
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
CREATE UNIQUE INDEX sale_client_unique ON sale_transactions (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX sale_farmer_date_idx ON sale_transactions (farmer_id, sale_date DESC);
CREATE TRIGGER sale_set_updated_at BEFORE UPDATE ON sale_transactions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- expenses                   (FARMER ONLY)
-- ---------------------------------------------------------------------------
CREATE TABLE expenses (
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
CREATE UNIQUE INDEX expense_client_unique ON expenses (farmer_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX expense_farmer_date_idx ON expenses (farmer_id, expense_date DESC);
CREATE TRIGGER expense_set_updated_at BEFORE UPDATE ON expenses
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- satellite_ndvi
--   status: GENERATED | FAILED ; stress derived from mean_ndvi_value thresholds
-- ---------------------------------------------------------------------------
CREATE TABLE satellite_ndvi (
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
CREATE INDEX satellite_plot_idx ON satellite_ndvi (plot_id, satellite_date DESC NULLS LAST);
CREATE INDEX satellite_farmer_idx ON satellite_ndvi (farmer_id);

COMMIT;
