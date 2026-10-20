import { describe, it, expect } from '@jest/globals';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { toPlotObservation, toPlotEvent, toPlotAlert } from '../../src/utils/serialize.js';

/**
 * Schema-contract tests for migration 007.
 *
 * There is no PostgreSQL in CI, so the SQL cannot be executed here. This file
 * closes the gap that actually bites: the migration and the code that reads it
 * drifting apart. An unknown column or a renamed field only shows up at runtime
 * against a live database, which is exactly the kind of bug that reaches
 * production.
 *
 * It also pins the properties that make the file safe on *both* apply paths:
 * `npm run migrate` (which wraps it in its own transaction) and a manual paste
 * into the Supabase SQL editor followed by a later deploy. That second path only
 * works because every statement is idempotent, and PostgreSQL has no
 * `CREATE TRIGGER IF NOT EXISTS`, so the triggers must be dropped first.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SQL = readFileSync(path.resolve(__dirname, '../../migrations/007_satellite_monitoring.sql'), 'utf8');

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

/** Serializer output keys are camelCase; its `*Id` keys map onto a plain `id`. */
const KEY_ALIASES = {
  observationId: 'id',
  eventId: 'id',
  alertId: 'id',
  detectedKey: 'event_key',
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

describe('migration 007 - structure', () => {
  it('creates the three monitoring tables', () => {
    for (const table of ['plot_observations', 'plot_events', 'plot_alerts']) {
      expect(columnsOf(table)).not.toBeNull();
    }
  });

  it('is wrapped in a transaction, like every other migration', () => {
    expect(SQL).toMatch(/^BEGIN;/m);
    expect(SQL.trimEnd().endsWith('COMMIT;')).toBe(true);
  });

  it('only adds objects and never alters an existing table', () => {
    // 007 has to be additive: it is applied to databases that already hold real
    // farmer data, and it must not reshape anything that predates it.
    expect(SQL).not.toMatch(/ALTER TABLE\s+(?!plot_observations|plot_events|plot_alerts)\w+/i);
    expect(SQL).not.toMatch(/DROP TABLE/i);
    expect(SQL).not.toMatch(/DROP COLUMN/i);
    expect(SQL).not.toMatch(/DELETE FROM/i);
  });

  it('keeps updated_at honest with a trigger, as 001 and 002 do', () => {
    for (const table of ['plot_observations', 'plot_events', 'plot_alerts']) {
      expect(SQL).toMatch(new RegExp(`CREATE TRIGGER\\s+\\w+_set_updated_at BEFORE UPDATE ON ${table}`));
      expect(SQL).toMatch(new RegExp(`FOR EACH ROW EXECUTE FUNCTION set_updated_at\\(\\)`));
    }
  });
});

describe('migration 007 - safe to apply twice', () => {
  // The repository can be deployed to Render (migrate runs on start) *after* the
  // file was pasted into the Supabase SQL editor. Both paths must succeed.
  it('guards every table, index and view with IF NOT EXISTS / OR REPLACE', () => {
    const created = SQL.match(/^CREATE (?:TABLE|UNIQUE INDEX|INDEX|VIEW)[^;]*/gim) ?? [];
    expect(created.length).toBeGreaterThan(0);
    for (const statement of created) {
      const guarded = /IF NOT EXISTS/i.test(statement) || /OR REPLACE/i.test(statement);
      expect(`${statement.slice(0, 60)} guarded=${guarded}`).toBe(`${statement.slice(0, 60)} guarded=true`);
    }
  });

  it('drops each trigger before recreating it', () => {
    // `CREATE TRIGGER IF NOT EXISTS` does not exist in PostgreSQL, so a bare
    // CREATE would abort a second run.
    // Anchored to the start of a line so the prose in the header comment
    // (which literally mentions `CREATE TRIGGER IF NOT EXISTS`) is not counted.
    const creates = SQL.match(/^CREATE TRIGGER\s+(\w+)/gm) ?? [];
    expect(creates.length).toBe(3);
    for (const create of creates) {
      const name = create.replace('CREATE TRIGGER ', '');
      expect(SQL).toMatch(new RegExp(`DROP TRIGGER IF EXISTS ${name} ON `));
    }
  });

  it('enables RLS idempotently and only on its own tables', () => {
    expect(SQL).toMatch(/ENABLE ROW LEVEL SECURITY/);
    const rlsBlock = /\$rls\$([\s\S]*?)\$rls\$/.exec(SQL);
    expect(rlsBlock).not.toBeNull();
    expect(rlsBlock[1]).toMatch(/plot_observations/);
    expect(rlsBlock[1]).toMatch(/plot_events/);
    expect(rlsBlock[1]).toMatch(/plot_alerts/);
    // Never FORCE: the API connects as the owner and would lock itself out.
    expect(SQL).not.toMatch(/FORCE ROW LEVEL SECURITY/i);
  });
});

describe('migration 007 - columns match the serializers', () => {
  it('plot_observations satisfies toPlotObservation', () => {
    assertSerializerMatchesSchema(toPlotObservation, 'plot_observations');
  });

  it('plot_events satisfies toPlotEvent', () => {
    assertSerializerMatchesSchema(toPlotEvent, 'plot_events');
  });

  it('plot_alerts satisfies toPlotAlert', () => {
    assertSerializerMatchesSchema(toPlotAlert, 'plot_alerts');
  });

  it('exposes the columns the monitoring service writes', () => {
    // Kept explicit because these are the fields a silent rename would break.
    const observations = columnsOf('plot_observations');
    for (const column of ['plot_id', 'farmer_id', 'observed_on', 'is_simulated', 'valid_pixel_pct', 'cloud_cover_pct']) {
      expect(observations.has(column)).toBe(true);
    }
    const events = columnsOf('plot_events');
    for (const column of ['event_key', 'client_id', 'classification', 'confirmed', 'recorded_by', 'weather_context']) {
      expect(events.has(column)).toBe(true);
    }
    const alerts = columnsOf('plot_alerts');
    for (const column of ['alert_key', 'alert_type', 'severity', 'acknowledged_at', 'dismissed_at']) {
      expect(alerts.has(column)).toBe(true);
    }
  });
});

describe('migration 007 - integrity rules', () => {
  it('deduplicates observations per plot and date', () => {
    expect(SQL).toMatch(/plot_observations_unique UNIQUE \(plot_id, observed_on\)/);
  });

  it('deduplicates a detected event per plot and key', () => {
    expect(SQL).toMatch(/plot_events_detected_key_unique UNIQUE \(plot_id, event_key\)/);
  });

  it('deduplicates an alert per plot and stable key', () => {
    expect(SQL).toMatch(/plot_alerts_key_unique UNIQUE \(plot_id, alert_key\)/);
  });

  it('makes the offline client id unique per plot, and only when present', () => {
    // A partial index: NULL client_id (an online create) must stay unlimited.
    expect(SQL).toMatch(/CREATE UNIQUE INDEX IF NOT EXISTS idx_plot_events_client_unique[\s\S]*?WHERE client_id IS NOT NULL/);
  });

  it('constrains indices to the real NDVI/EVI range', () => {
    expect(SQL).toMatch(/ndvi\s+numeric\(8, 4\) CHECK \(ndvi IS NULL OR \(ndvi >= -1 AND ndvi <= 1\)\)/);
    expect(SQL).toMatch(/evi\s+numeric\(8, 4\) CHECK \(evi IS NULL OR \(evi >= -1 AND evi <= 1\)\)/);
  });

  it('constrains the enumerated columns', () => {
    expect(SQL).toMatch(/quality IN \('GOOD', 'FAIR', 'POOR'\)/);
    expect(SQL).toMatch(/source IN \('FARMER', 'SYSTEM'\)/);
    expect(SQL).toMatch(/severity IN \('INFO', 'WARNING', 'CRITICAL'\)/);
    expect(SQL).toMatch(/status IN \('OPEN', 'ACKNOWLEDGED', 'DISMISSED'\)/);
    // The engine never asserts a cause, so no POSSIBLE_* kind exists.
    expect(SQL).not.toMatch(/POSSIBLE_HARVEST/);
  });

  it('cascades deletes from the plot and the farmer', () => {
    expect(SQL).toMatch(/plot_id\s+uuid NOT NULL REFERENCES farm_plots\(id\) ON DELETE CASCADE/);
    expect(SQL).toMatch(/farmer_id\s+uuid NOT NULL REFERENCES users\(id\) ON DELETE CASCADE/);
  });

  it('deletes rather than nulls the plot on an observation or alert', () => {
    // An orphaned observation would be attributed to no plot at all, which is
    // worse than losing it — the plot is the only thing that gives it meaning.
    const observationBlock = columnsOf('plot_observations');
    expect(observationBlock.has('plot_id')).toBe(true);
    expect(SQL).not.toMatch(/plot_id\s+uuid REFERENCES farm_plots\(id\) ON DELETE SET NULL/);
  });
});

describe('migration 007 - label counting', () => {
  it('counts only confirmed farmer events, never the system suggestions', () => {
    const view = /CREATE OR REPLACE VIEW plot_event_label_counts AS([\s\S]*?);/.exec(SQL);
    expect(view).not.toBeNull();
    // Training on unconfirmed suggestions would be training on the model's own
    // output, so the view is required to filter on `confirmed`.
    expect(view[1]).toMatch(/source = 'FARMER' AND confirmed/);
    expect(view[1]).toMatch(/total_farmer_labels/);
    expect(view[1]).toMatch(/harvest_or_clearing_labels/);
  });

  it('does not train anything', () => {
    expect(SQL).not.toMatch(/CREATE MODEL/i);
    expect(SQL).not.toMatch(/ml_/i);
  });
});
