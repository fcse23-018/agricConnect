import { describe, it, expect } from '@jest/globals';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { toHerdEvent, toHerdSummary, toListing } from '../../src/utils/serialize.js';

/**
 * Schema-contract tests for migration 009 (the herd register and per-animal
 * listing attributes - report CR1 and CR2).
 *
 * There is no PostgreSQL in CI, so the SQL cannot be executed here. This file
 * closes the gap that actually bites: the migration and the code that reads it
 * drifting apart. An unknown column or a renamed field only shows up at runtime
 * against a live database, which is exactly the kind of bug that reaches
 * production.
 *
 * It also pins the properties that make the file safe on *both* apply paths:
 * `npm run migrate` (which wraps it in its own transaction) and a manual paste
 * into the Supabase SQL editor followed by a later deploy. That second path
 * only works because every statement is idempotent - and PostgreSQL has no
 * `CREATE TRIGGER IF NOT EXISTS` or `ADD COLUMN IF NOT EXISTS ... IF NOT` mixup
 * to fall back on, so the guards must be exactly right.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SQL = readFileSync(path.resolve(__dirname, '../../migrations/009_herd_register.sql'), 'utf8');

/** Columns declared in a `CREATE TABLE IF NOT EXISTS <table> ( ... );` block. */
function columnsOf(table) {
  const block = new RegExp(
    `CREATE TABLE IF NOT EXISTS\\s+${table}\\s*\\(([\\s\\S]*?)\\n\\);`,
  ).exec(SQL);
  if (!block) return null;

  const columns = new Set();
  for (const line of block[1].split('\n')) {
    // `  column_name  type ...` — constraints start with an uppercase keyword.
    const match = /^\s{2}([a-z_][a-z0-9_]*)\s+[a-z]/.exec(line);
    if (match) columns.add(match[1]);
  }
  return columns;
}

/** Columns added to an existing table via `ADD COLUMN IF NOT EXISTS`. */
function addedColumns() {
  const added = new Set();
  for (const match of SQL.matchAll(/ADD COLUMN IF NOT EXISTS\s+([a-z_][a-z0-9_]*)/g)) {
    added.add(match[1]);
  }
  return added;
}

/** Serializer output keys are camelCase; its `*Id` keys map onto a plain `id`. */
const KEY_ALIASES = {
  eventId: 'id',
};

function snakeCase(key) {
  return key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
}

/**
 * Every key a serializer emits must correspond to a column in the migration.
 * The serializer is the real consumer, so deriving the expectation from it means
 * this test fails when *either* side changes without the other.
 */
function assertSerializerMatchesSchema(serialize, table) {
  const columns = columnsOf(table);
  expect(columns).not.toBeNull();

  const emitted = Object.keys(serialize({}));
  const missing = emitted
    .map((key) => KEY_ALIASES[key] ?? snakeCase(key))
    .filter((column) => !columns.has(column));

  expect(missing).toEqual([]);
}

describe('migration 009 - structure', () => {
  it('creates the herd register table', () => {
    expect(columnsOf('herd_events')).not.toBeNull();
  });

  it('adds the per-animal listing attributes to produce_listings', () => {
    const added = addedColumns();
    for (const column of ['animal_class', 'age_months', 'weight_kg', 'tag_number']) {
      expect(added.has(column)).toBe(true);
    }
  });

  it('is wrapped in a transaction, like every other migration', () => {
    expect(SQL).toMatch(/^BEGIN;/m);
    expect(SQL.trimEnd().endsWith('COMMIT;')).toBe(true);
  });

  it('only widens existing tables and never drops or reshapes anything', () => {
    // 009 is applied to databases that already hold real farmer data. The only
    // existing table it may touch is produce_listings, and only additively.
    expect(SQL).not.toMatch(/ALTER TABLE\s+(?!produce_listings)\w+/i);
    expect(SQL).not.toMatch(/ALTER COLUMN/i);
    expect(SQL).not.toMatch(/DROP TABLE/i);
    expect(SQL).not.toMatch(/DROP COLUMN/i);
    expect(SQL).not.toMatch(/DELETE FROM/i);
  });

  it('keeps updated_at honest with a trigger, as 001 and 002 do', () => {
    expect(SQL).toMatch(/CREATE TRIGGER\s+herd_events_set_updated_at BEFORE UPDATE ON herd_events/);
    expect(SQL).toMatch(/FOR EACH ROW EXECUTE FUNCTION set_updated_at\(\)/);
  });
});

describe('migration 009 - safe to apply twice', () => {
  // The repository can be deployed to Render (migrate runs on start) *after* the
  // file was pasted into the Supabase SQL editor. Both paths must succeed.
  it('guards every added column with IF NOT EXISTS', () => {
    // Prose in the header comment mentions `ADD COLUMN IF NOT EXISTS` too, so
    // strip comment lines first — only executable statements count.
    const codeOnly = SQL.split('\n').filter((line) => !line.trim().startsWith('--')).join('\n');
    // Case-insensitive because `IF NOT EXISTS` is uppercase in the file; the
    // column names are lowercase, and both must be counted either way.
    const adds = codeOnly.match(/ADD COLUMN(?:\s+IF NOT EXISTS)?\s+[a-z_][a-z0-9_]*/gi) ?? [];
    expect(adds.length).toBeGreaterThanOrEqual(4);
    const guarded = codeOnly.match(/ADD COLUMN\s+IF NOT EXISTS\s+[a-z_][a-z0-9_]*/gi) ?? [];
    expect(guarded.length).toBe(adds.length);
  });

  it('guards every table, index and view with IF NOT EXISTS / OR REPLACE', () => {
    const created = SQL.match(/^CREATE (?:TABLE|UNIQUE INDEX|INDEX|VIEW)[^;]*/gim) ?? [];
    expect(created.length).toBeGreaterThan(0);
    for (const statement of created) {
      const guarded = /IF NOT EXISTS/i.test(statement) || /OR REPLACE/i.test(statement);
      expect(`${statement.slice(0, 60)} guarded=${guarded}`).toBe(`${statement.slice(0, 60)} guarded=true`);
    }
  });

  it('drops the trigger before recreating it', () => {
    // `CREATE TRIGGER IF NOT EXISTS` does not exist in PostgreSQL, so a bare
    // CREATE would abort a second run.
    const creates = SQL.match(/^CREATE TRIGGER\s+(\w+)/gm) ?? [];
    expect(creates.length).toBe(1);
    for (const create of creates) {
      const name = create.replace('CREATE TRIGGER ', '');
      expect(SQL).toMatch(new RegExp(`DROP TRIGGER IF EXISTS ${name} ON `));
    }
  });

  it('enables RLS idempotently and only on its own table', () => {
    expect(SQL).toMatch(/ENABLE ROW LEVEL SECURITY/);
    const rlsBlock = /\$rls\$([\s\S]*?)\$rls\$/.exec(SQL);
    expect(rlsBlock).not.toBeNull();
    expect(rlsBlock[1]).toMatch(/herd_events/);
    // Never FORCE: the API connects as the owner and would lock itself out.
    expect(SQL).not.toMatch(/FORCE ROW LEVEL SECURITY/i);
  });
});

describe('migration 009 - columns match the serializers', () => {
  it('herd_events satisfies toHerdEvent', () => {
    assertSerializerMatchesSchema(toHerdEvent, 'herd_events');
  });

  it('the listing attributes satisfy toListing', () => {
    // toListing also emits the pre-009 columns; here only the new ones can be
    // checked against this migration, and each must exist as an added column.
    const added = addedColumns();
    const emitted = Object.keys(toListing({}));
    const newKeys = ['animalClass', 'ageMonths', 'weightKg', 'tagNumber'];
    for (const key of newKeys) {
      expect(emitted).toContain(key);
      expect(added.has(snakeCase(key))).toBe(true);
    }
  });

  it('exposes the columns the register writes', () => {
    // Kept explicit because these are the fields a silent rename would break.
    const events = columnsOf('herd_events');
    for (const column of [
      'event_type', 'event_date', 'head_count', 'weight_kg', 'unit_price',
      'amount', 'tag_number', 'client_id', 'created_by',
    ]) {
      expect(events.has(column)).toBe(true);
    }
  });
});

describe('migration 009 - integrity rules', () => {
  it('constrains the register to the event types the field study named', () => {
    expect(SQL).toMatch(/event_type IN \('BIRTH', 'DEATH', 'SALE', 'WEIGHING', 'DIPPING', 'VETERINARY', 'OTHER'\)/);
  });

  it('keeps every per-animal attribute nullable', () => {
    // A produce listing describes a sack of maize as often as an animal; the
    // new columns must never become required for the produce case.
    expect(SQL).toMatch(/animal_class text\s*\n\s*CHECK \(animal_class IS NULL/);
    expect(SQL).toMatch(/age_months integer\s*\n\s*CHECK \(age_months IS NULL/);
    expect(SQL).toMatch(/weight_kg numeric\(10,2\)\s*\n\s*CHECK \(weight_kg IS NULL/);
    expect(SQL).toMatch(/tag_number text\s*\n\s*CHECK \(tag_number IS NULL/);
  });

  it('makes the offline client id unique per farmer, and only when present', () => {
    // A partial index: NULL client_id (an online create) must stay unlimited.
    expect(SQL).toMatch(
      /CREATE UNIQUE INDEX IF NOT EXISTS herd_client_unique[\s\S]*?WHERE client_id IS NOT NULL/,
    );
  });

  it('caps age_months within a plausible lifespan', () => {
    expect(SQL).toMatch(/age_months BETWEEN 0 AND 600/);
  });

  it('keeps head_count at one or more so totals can sum it', () => {
    expect(SQL).toMatch(/head_count\s+integer NOT NULL DEFAULT 1 CHECK \(head_count >= 1\)/);
  });
});

describe('migration 009 - automatic totals (the summary view)', () => {
  it('computes the totals the keepers asked for, per animal type', () => {
    const view = /CREATE OR REPLACE VIEW herd_register_summary AS([\s\S]*?);/.exec(SQL);
    expect(view).not.toBeNull();
    for (const column of ['births', 'deaths', 'head_sold', 'sale_proceeds', 'last_dipped_on', 'last_veterinary_on']) {
      expect(view[1]).toMatch(new RegExp(`AS ${column}\\b`));
    }
  });

  it('excludes soft-deleted entries so corrections leave the totals immediately', () => {
    const view = /CREATE OR REPLACE VIEW herd_register_summary AS([\s\S]*?);/.exec(SQL);
    expect(view).not.toBeNull();
    expect(view[1]).toMatch(/WHERE deleted_at IS NULL/);
  });

  it('satisfies toHerdSummary', () => {
    // The view is the source of the summary serializer; every emitted key must
    // appear in the view's SELECT list.
    const view = /CREATE OR REPLACE VIEW herd_register_summary AS([\s\S]*?);/.exec(SQL);
    expect(view).not.toBeNull();

    const emitted = Object.keys(toHerdSummary({}));
    const missing = emitted
      .map((key) => (key === 'farmerId' ? 'farmer_id' : snakeCase(key)))
      .filter((column) => !view[1].includes(column));

    expect(missing).toEqual([]);
  });

  it('does not train anything', () => {
    expect(SQL).not.toMatch(/CREATE MODEL/i);
    expect(SQL).not.toMatch(/ml_/i);
  });
});
