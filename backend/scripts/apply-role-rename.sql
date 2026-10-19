-- ============================================================================
-- AgriConnect · apply-role-rename.sql
--
-- MANUAL script for the Supabase SQL editor. It applies the same change as
-- `backend/migrations/006_role_rename.sql`, but *idempotently*, so it is safe to
-- paste and run whether the database is still on the old labels or already on the
-- new ones, and safe to run before **or** after the next Render deploy.
--
-- WHY THIS EXISTS
--   Render runs `npm run migrate && npm start`. On the next deploy it will apply
--   006_role_rename.sql by itself. That migration is not idempotent (a second run
--   would try to rename FARMER → FARM_OWNER when FARM_OWNER already exists and
--   would abort, which would stop the API from starting). This script therefore
--   (a) guards the rename, and (b) records the migration as applied, so the two
--   paths can never fight:
--
--     run this script first  → deploy sees 006 as applied and skips it
--     deploy first           → this script finds the new labels and does nothing
--
-- THE CHANGE
--   old FARMER        → FARM_OWNER   owns the farm, sells, employs, sees finances, NDVI
--   old FARM_EMPLOYEE → FARMER       works the land, records field data
--   BUYER             → BUYER        unchanged
--
--   The rename is *in place* (`ALTER TYPE … RENAME VALUE`), so no row is touched:
--   every existing account keeps exactly the capabilities it had and only the label
--   it is shown changes. All three roles may buy.
--
-- NOT COVERED HERE (no schema change is needed for these):
--   · the profile/avatar image fix      — application code only
--   · the post-NDVI advice feature      — computed per request, nothing stored
--   · the plot boundary                 — `farm_plots.geojson_boundary` exists since 002
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Rename the enum labels, but only if they are still the old ones.
--    (FARMER must be vacated before FARM_EMPLOYEE can take that name.)
-- ---------------------------------------------------------------------------
DO $roles$
BEGIN
  IF to_regtype('user_role') IS NULL THEN
    RAISE NOTICE 'user_role does not exist yet - no action taken';
    RETURN;
  END IF;

  IF EXISTS (
       SELECT 1 FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
        WHERE t.typname = 'user_role' AND e.enumlabel = 'FARMER'
     )
     AND EXISTS (
       SELECT 1 FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
        WHERE t.typname = 'user_role' AND e.enumlabel = 'FARM_EMPLOYEE'
     )
  THEN
    ALTER TYPE user_role RENAME VALUE 'FARMER'        TO 'FARM_OWNER';
    ALTER TYPE user_role RENAME VALUE 'FARM_EMPLOYEE' TO 'FARMER';
    RAISE NOTICE 'user_role renamed: FARMER -> FARM_OWNER, FARM_EMPLOYEE -> FARMER';
  ELSE
    RAISE NOTICE 'user_role already carries the new labels - nothing to rename';
  END IF;
END
$roles$;


-- ---------------------------------------------------------------------------
-- 2. The default role is the one that owns the farm, and 005's employer-link
--    trigger must compare against the *new* label: after the rename the literal
--    'FARMER' means the worker, which would let a worker employ someone and stop
--    an owner from doing so.
-- ---------------------------------------------------------------------------
DO $fn$
BEGIN
  IF to_regclass('public.users') IS NULL THEN
    RAISE NOTICE 'users table missing - no action taken';
    RETURN;
  END IF;

  ALTER TABLE users ALTER COLUMN role SET DEFAULT 'FARM_OWNER';

  EXECUTE $ddl$
    CREATE OR REPLACE FUNCTION enforce_employer_link() RETURNS trigger AS $body$
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
    $body$ LANGUAGE plpgsql;
  $ddl$;

  RAISE NOTICE 'users.role default set to FARM_OWNER; enforce_employer_link() recreated';
END
$fn$;


-- ---------------------------------------------------------------------------
-- 3. Record the migration so the deploy's `npm run migrate` does not run the
--    non-idempotent 006 file on top of this.
-- ---------------------------------------------------------------------------
DO $mark$
BEGIN
  -- Same DDL as the runner's ensureMigrationsTable(), so creating it here can
  -- never disagree with the table the deploy would have made.
  CREATE TABLE IF NOT EXISTS schema_migrations (
    filename    text PRIMARY KEY,
    applied_at  timestamptz NOT NULL DEFAULT now(),
    checksum    text
  );

  INSERT INTO schema_migrations (filename) VALUES ('006_role_rename.sql')
  ON CONFLICT (filename) DO NOTHING;

  RAISE NOTICE 'recorded 006_role_rename.sql as applied';
END
$mark$;


-- ---------------------------------------------------------------------------
-- 4. Verification — the roles can all buy; the enum order is the enum order.
--    Expected: FARM_OWNER, FARMER, BUYER (in that order).
-- ---------------------------------------------------------------------------
SELECT e.enumlabel AS role_label
  FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid
 WHERE t.typname = 'user_role'
 ORDER BY e.enumsortorder;

-- Expected: 'FARM_OWNER'::user_role
SELECT column_default AS users_role_default
  FROM information_schema.columns
 WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'role';

-- Sanity check on live data: existing owners stayed owners.
SELECT role, COUNT(*) AS accounts
  FROM users
 GROUP BY role
 ORDER BY role;

-- Expected: 001..006 listed, including 006_role_rename.sql
SELECT filename, applied_at FROM schema_migrations ORDER BY filename;
