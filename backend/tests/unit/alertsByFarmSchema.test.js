import { describe, it, expect } from '@jest/globals';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Schema-contract guard for `alertsByFarm`.
 *
 * The farm-wide attention summary groups every one of the owner's plots and is
 * the endpoint that answers "which of my farms need me today?". It shipped with
 * a query that referenced a `farms` table and a `farm_plots.farm_id` column that
 * do not exist in this schema, plus `plot_alerts.kind` instead of `alert_type`.
 * There is no PostgreSQL in CI, so nothing caught it and the endpoint returned
 * HTTP 500 in production.
 *
 * This file closes that gap the way `migration007.test.js` does: it reads the
 * service source, pulls out the SQL it sends, and checks every table and every
 * qualified column reference against the columns the migrations actually
 * declare. A query may only touch columns that exist.
 */

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVICE_PATH = path.resolve(
  __dirname,
  '../../src/modules/satellite/satellite.monitoring.service.js',
);
const MIGRATIONS_DIR = path.resolve(__dirname, '../../migrations');

const SERVICE = readFileSync(SERVICE_PATH, 'utf8');

/** Every column declared by every migration, keyed by table. */
function buildSchema() {
  const schema = new Map();
  const files = readdirSync(MIGRATIONS_DIR).filter((name) => name.endsWith('.sql'));

  for (const file of files) {
    const sql = readFileSync(path.join(MIGRATIONS_DIR, file), 'utf8');
    // CREATE TABLE [IF NOT EXISTS] <name> ( ... );
    const tableRe = /CREATE TABLE(?:\s+IF NOT EXISTS)?\s+([a-z_][a-z0-9_]*)\s*\(([\s\S]*?)\n\);/gi;
    for (const match of sql.matchAll(tableRe)) {
      const [, table, body] = match;
      const columns = schema.get(table) ?? new Set();
      for (const line of body.split('\n')) {
        // `  column_name  type ...`; constraint lines start with an uppercase word.
        const column = /^\s{2}([a-z_][a-z0-9_]*)\s+[a-z]/.exec(line);
        if (column) columns.add(column[1]);
      }
      schema.set(table, columns);
    }
  }
  return schema;
}

const SCHEMA = buildSchema();

/** The SQL string literals passed to `many(...)` inside one exported function. */
function queriesIn(functionName) {
  const start = SERVICE.indexOf(`export async function ${functionName}(`);
  expect(start).toBeGreaterThanOrEqual(0);

  // The body ends at the next top-level `export` after the function.
  const rest = SERVICE.slice(start + 1);
  const endRel = rest.indexOf('\nexport ');
  const body = SERVICE.slice(start, endRel === -1 ? undefined : start + 1 + endRel);

  const queries = [];
  const re = /many\(\s*`([\s\S]*?)`/g;
  for (const match of body.matchAll(re)) queries.push(match[1]);
  return queries;
}

/**
 * A table alias like `a` or `p` maps to the table named in `FROM`/`JOIN`. We
 * only understand the subset the service uses: plain `FROM t` / `JOIN t` with
 * an optional alias. Anything we cannot resolve is skipped rather than guessed,
 * so the test stays honest about what it actually proves.
 */
function aliasMap(sql) {
  const aliases = new Map();
  const re = /\b(?:FROM|JOIN)\s+(?!LATERAL\b|\()([a-z_][a-z0-9_]*)(?:\s+(?:AS\s+)?([a-z][a-z0-9_]*))?/gi;
  for (const match of sql.matchAll(re)) {
    const [, table, aliasRaw] = match;
    if (!SCHEMA.has(table)) continue;
    // A keyword after the table name is not an alias.
    const keyword = new Set(['where', 'on', 'left', 'join', 'inner', 'order', 'group', 'and', 'using', 'limit']);
    const alias = aliasRaw && !keyword.has(aliasRaw.toLowerCase()) ? aliasRaw : table;
    aliases.set(alias, table);
    aliases.set(table, table);
  }
  return aliases;
}

/** Qualified `alias.column` references that a known table alias precedes. */
function qualifiedColumns(sql) {
  const aliases = aliasMap(sql);
  const found = [];
  for (const match of sql.matchAll(/\b([a-z][a-z0-9_]*)\.([a-z_][a-z0-9_]*)\b/gi)) {
    const [, qualifier, column] = match;
    const table = aliases.get(qualifier);
    if (table) found.push({ table, column, qualifier });
  }
  return found;
}

/** Any `FROM`/`JOIN` target that the migrations do not create. */
function unknownTables(sql) {
  const known = new Set(SCHEMA.keys());
  const missing = [];
  // Skip derived tables: `FROM LATERAL (`, `FROM (`, and CTEs are not tables.
  const re = /\b(?:FROM|JOIN)\s+(?!LATERAL\b|\()([a-z_][a-z0-9_]*)/gi;
  for (const match of sql.matchAll(re)) {
    if (!known.has(match[1])) missing.push(match[1]);
  }
  return missing;
}

describe('alertsByFarm - SQL matches the migrations', () => {
  const queries = queriesIn('alertsByFarm');

  it('issues its queries', () => {
    expect(queries.length).toBeGreaterThanOrEqual(2);
  });

  it('only reads tables the migrations create', () => {
    for (const sql of queries) {
      expect(unknownTables(sql)).toEqual([]);
    }
  });

  it('never invents a farms table or a farm_plots.farm_id column', () => {
    // The exact defect that returned HTTP 500: this schema has no farm entity,
    // a plot belongs to its owner's account. Guard against the idea coming back.
    expect(SCHEMA.has('farms')).toBe(false);
    expect(SCHEMA.get('farm_plots').has('farm_id')).toBe(false);
    for (const sql of queries) {
      expect(sql).not.toMatch(/\bfarms\b/i);
      expect(sql).not.toMatch(/farm_id/i);
    }
  });

  it('reads only columns that exist on their table', () => {
    const problems = [];
    for (const sql of queries) {
      for (const { table, column, qualifier } of qualifiedColumns(sql)) {
        if (!SCHEMA.get(table).has(column)) {
          problems.push(`${qualifier}.${column} (${table}.${column})`);
        }
      }
    }
    expect(problems).toEqual([]);
  });

  it('selects plot_alerts.alert_type, not a kind column', () => {
    // `plot_alerts` has `alert_type`; `kind` was the wrong name.
    expect(SCHEMA.get('plot_alerts').has('alert_type')).toBe(true);
    expect(SCHEMA.get('plot_alerts').has('kind')).toBe(false);
    // The alert query is the one that actually selects alert_type (the plot
    // query only reaches plot_alerts inside a COUNT subquery).
    const alertQuery = queries.find((sql) => /a\.alert_type\b/i.test(sql));
    expect(alertQuery).toBeDefined();
    expect(alertQuery).toMatch(/alert_type/);
    expect(alertQuery).not.toMatch(/a\.kind\b/);
  });

  it('joins plot_observations on columns that exist', () => {
    const obsQuery = queries.find((sql) => /FROM plot_observations/i.test(sql));
    expect(obsQuery).toBeDefined();
    for (const column of ['observed_on', 'ndvi', 'quality', 'is_simulated', 'source']) {
      expect(obsQuery).toMatch(new RegExp(`\\b${column}\\b`));
    }
  });

  it('scopes every query to one farmer', () => {
    for (const sql of queries) {
      expect(sql).toMatch(/farmer_id\s*=\s*\$1/);
    }
  });
});
