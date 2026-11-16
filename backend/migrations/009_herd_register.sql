-- ============================================================================
-- AgriConnect - 009_herd_register.sql
--
-- Two changes from the academic report's field findings, both about livestock:
--
-- CR1 - a herd register. `herd_events` records births, deaths, weighings, sales
--       and dipping/veterinary dates per animal type, with the offline-first
--       `client_id` key every other farm table uses. The
--       `herd_register_summary` view computes the automatic totals the keepers
--       asked for (births, deaths, head sold, sale proceeds, last weighing /
--       dipping / veterinary date) so no code has to keep the sums in step by
--       hand and they can never drift from the entries.
--
-- CR2 - per-animal listing attributes. `produce_listings` gains `animal_class`,
--       `age_months`, `weight_kg` and `tag_number`, so an animal can be listed
--       as what it is ("a 30-month Bonsmara heifer, ear tag BW0123") instead of
--       a price per kilogram. All four are nullable: a sack of maize has none
--       of them, and a listing must never force a farmer to invent data.
--
-- Additive and idempotent: existing columns are only widened with
-- ADD COLUMN IF NOT EXISTS, the new table/indexes use IF NOT EXISTS, the view
-- is CREATE OR REPLACE, and the trigger is dropped before being recreated.
-- Safe to run more than once and safe to paste into the Supabase SQL editor as
-- an alternative to `npm run migrate`.
-- ============================================================================
BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Per-animal listing attributes (CR2)
-- ---------------------------------------------------------------------------
-- `age_months` is capped at 600 (50 years): beyond that it is a typo, not an
-- animal. `weight_kg` / `tag_number` describe the animal(s) on offer; the price
-- column already on the table is the price per unit, and `unit = 'head'` makes
-- that a price per animal.
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

-- ---------------------------------------------------------------------------
-- 2. The herd register (CR1)
-- ---------------------------------------------------------------------------
-- One row is one register entry. `head_count` is how many animals the entry
-- concerns (3 calves born; 1 tagged animal weighed), so the summary view can
-- add births and deaths without a second table. The money columns follow
-- `sale_transactions`: `unit_price` is per animal, `amount` the entry total.
-- Both are nullable - a dipping date has neither.
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

DROP TRIGGER IF EXISTS herd_events_set_updated_at ON herd_events;
CREATE TRIGGER herd_events_set_updated_at BEFORE UPDATE ON herd_events
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- 3. Automatic totals (CR1)
-- ---------------------------------------------------------------------------
-- The register's headline numbers, per animal type, computed rather than
-- stored so they can never disagree with the entries. Soft-deleted rows are
-- excluded: a corrected entry disappears from the totals the moment it is
-- deleted. Animal types with no surviving entries are simply absent.
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
-- 4. Row Level Security (same hardening as 007 section 5)
-- ---------------------------------------------------------------------------
-- The API connects as the table owner, and RLS never applies to an owner
-- unless FORCE is set (deliberately NOT set). Enabling RLS with no policies
-- therefore blocks Supabase's PostgREST/anon key from reading a keeper's herd
-- register while leaving the backend untouched.
DO $rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['herd_events'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
  END LOOP;
END
$rls$;

COMMIT;
