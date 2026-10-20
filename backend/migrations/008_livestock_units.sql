-- ============================================================================
-- AgriConnect A� 008_livestock_units.sql
-- Allow livestock to be sold as a whole group, not only as weight or per head.
--
-- Farmers sell animals two ways: individually ("20 head of cattle") and as one
-- lot ("the herd"). `head` already existed; this adds `herd` (cattle, goats,
-- donkeys - any mixed/herd animal) and `flock` (sheep, goats, poultry - animals
-- kept as a flock). Selling "1 herd" means the buyer is buying the group for a
-- single price, which is how livestock is actually traded at the auction pens.
--
-- Additive and idempotent: it only widens an existing CHECK constraint, drops any
-- previous copy of the new constraint first, and never touches existing rows. Safe
-- to run more than once and safe to paste into the Supabase SQL editor as an
-- alternative to `npm run migrate`.
--
-- NOTE: matching the change in code is required, not optional. The same list lives
-- in `src/modules/marketplace/marketplace.schemas.js` (LISTING_UNITS) and is
-- reused by the sync entity registry. If this migration is not applied, a listing
-- with unit 'herd' or 'flock' passes API validation and then fails at INSERT with
-- a database constraint error.
-- ============================================================================
BEGIN;

-- The original constraint on 003_marketplace.sql was defined inline, so PostgreSQL
-- generated its name. Find and drop it by whatever name it actually has, rather
-- than assuming `produce_listings_unit_check`.
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

-- Re-add the widened rule under a stable name so future migrations can target it
-- directly. The list must stay in step with LISTING_UNITS in the API schema.
ALTER TABLE produce_listings
  ADD CONSTRAINT produce_listings_unit_check
  CHECK (unit IN ('kg','tonne','bag','crate','bunch','head','herd','flock','litre'));

COMMIT;
