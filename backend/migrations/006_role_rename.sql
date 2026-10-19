-- ============================================================================
-- AgriConnect · 006_role_rename.sql
--
-- The three roles are renamed so they describe who a person actually is:
--
--   old FARMER        → FARM_OWNER   owns the farm, sells, employs, sees finances
--   old FARM_EMPLOYEE → FARMER       works the land, records field data
--   BUYER             → BUYER        unchanged
--
-- The rename is in place: `ALTER TYPE … RENAME VALUE` changes the enum label without
-- touching any row, so every existing account keeps exactly the capabilities it had
-- and only the label shown to it changes.
--
-- Safe to run on a database that already has the old labels (production) and on a
-- fresh one built by 001 (which creates the old labels, then this migration renames
-- them). Wrapped in a transaction, like every migration.
-- ============================================================================
BEGIN;

-- Order matters: FARMER must be vacated before FARM_EMPLOYEE can take that name.
ALTER TYPE user_role RENAME VALUE 'FARMER'        TO 'FARM_OWNER';
ALTER TYPE user_role RENAME VALUE 'FARM_EMPLOYEE' TO 'FARMER';

-- 001 left `DEFAULT 'FARMER'`. The label followed the rename to FARM_OWNER, but say
-- so explicitly: an account that does not state a role is the one that owns the farm.
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'FARM_OWNER';

-- 005's trigger was written against the old labels, where 'FARMER' meant the owner.
-- After the rename that literal means the worker, which would let a worker employ
-- someone and stop an owner from doing so. Re-create it against FARM_OWNER.
CREATE OR REPLACE FUNCTION enforce_employer_link() RETURNS trigger AS $$
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
$$ LANGUAGE plpgsql;

COMMIT;
