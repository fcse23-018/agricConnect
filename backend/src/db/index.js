import pg from 'pg';
import { config } from '../config/index.js';

const { Pool, types } = pg;

/**
 * Return PostgreSQL `date` columns as plain `YYYY-MM-DD` strings.
 *
 * Why this is not cosmetic. By default node-postgres parses a `date` (OID 1082)
 * into a JavaScript `Date` at *local* midnight. That breaks in two ways:
 *
 *  1. `String(date).slice(0, 10)` yields `"Thu Oct 22"` — a day-of-week string,
 *     not a date. Anything that formats or compares it then breaks: the satellite
 *     overview reported `newestObservation: "Thu Oct 22"` and
 *     `"NaN day(s) old"`, and the analysis engine found no usable reading in a
 *     series of 23 rows, so the screen showed "no observation" while the database
 *     held two dozen.
 *
 *  2. A `date` is a calendar date with no time and no zone, but parsing it at
 *     local midnight invents both. On a UTC server (production) a date of
 *     `2026-10-22` becomes `2026-10-21T22:00:00Z` in a +02:00 developer zone and
 *     `2026-10-21` once formatted — the observation silently moves to the
 *     previous day. Imagery dates must never drift: a farmer comparing two
 *     readings a day apart would be misled about which changed.
 *
 * A `date` has no time component to lose, so the text form IS the value. This is
 * also what the rest of the codebase already assumes: `observed_on`, `event_date`
 * and friends are compared and sliced as `YYYY-MM-DD` strings throughout.
 *
 * `timestamp`/`timestamptz` (1114/1184) are deliberately left as `Date` objects —
 * those do carry an instant, and `iso()` formats them to ISO-8601.
 */
types.setTypeParser(1082, (value) => (value === null ? null : String(value)));

export const pool = new Pool({
  ...(config.db.connectionString
    ? { connectionString: config.db.connectionString }
    : {
        host: config.db.host,
        port: config.db.port,
        database: config.db.database,
        user: config.db.user,
        password: config.db.password,
      }),
  ssl: config.db.ssl,
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 10_000,
});

pool.on('error', (err) => {
  // eslint-disable-next-line no-console
  console.error('[db] idle client error', err.message);
});

/** Run a parameterised query. Always use parameters — never string interpolation. */
export async function query(text, params) {
  const start = Date.now();
  const res = await pool.query(text, params);
  const ms = Date.now() - start;
  if (!config.isTest && ms > 300) {
    // eslint-disable-next-line no-console
    console.warn(`[db] slow query ${ms}ms: ${text.slice(0, 120)}`);
  }
  return res;
}

/** Convenience: first row or null. */
export async function one(text, params) {
  const res = await query(text, params);
  return res.rows[0] ?? null;
}

/** Convenience: all rows. */
export async function many(text, params) {
  const res = await query(text, params);
  return res.rows;
}

/**
 * Run `fn` inside a transaction. Commits on success, rolls back on any throw.
 * All marketplace/order and sync writes use this to stay consistent.
 */
export async function withTransaction(fn) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    try {
      await client.query('ROLLBACK');
    } catch {
      /* ignore rollback failure */
    }
    throw err;
  } finally {
    client.release();
  }
}

export async function healthcheck() {
  const res = await query('SELECT 1 AS ok');
  return res.rows[0]?.ok === 1;
}

export default { pool, query, one, many, withTransaction, healthcheck };
