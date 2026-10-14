import pg from 'pg';
import { config } from '../config/index.js';

const { Pool } = pg;

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
