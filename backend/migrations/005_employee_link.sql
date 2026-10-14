-- ============================================================================
-- AgriConnect · 005_employee_link.sql
-- INFERRED GAP FIX (see docs/00 §5 G8).
--
-- The documented class model gives Farm Employee the ability to "Log Crop
-- Harvest & Livestock" but provides no relationship between an employee and the
-- farm those records belong to. Without it, an employee's field records cannot
-- be attributed to a farmer and would be orphaned.
--
-- Resolution: a FARM_EMPLOYEE may be linked to exactly one employing FARMER.
-- All field records written by an employee are stored against the employer's
-- farmer_id, preserving the ownership model. Employees with no employer linked
-- receive a clear 403 on field-record writes.
-- ============================================================================
BEGIN;

ALTER TABLE users
  ADD COLUMN employer_farmer_id uuid REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX users_employer_idx ON users (employer_farmer_id) WHERE employer_farmer_id IS NOT NULL;

-- An employee cannot employ themselves, cannot employ others, and only a FARMER
-- may be an employer. Enforced with a trigger because CHECK cannot cross rows.
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
  IF employer_role <> 'FARMER' THEN
    RAISE EXCEPTION 'Only a FARMER can be an employer';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER users_enforce_employer BEFORE INSERT OR UPDATE OF employer_farmer_id ON users
  FOR EACH ROW EXECUTE FUNCTION enforce_employer_link();

COMMIT;
