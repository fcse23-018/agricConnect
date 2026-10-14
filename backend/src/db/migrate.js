import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pool, query } from './index.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MIGRATIONS_DIR = path.resolve(__dirname, '..', '..', 'migrations');

async function ensureMigrationsTable() {
  await query(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      filename    text PRIMARY KEY,
      applied_at  timestamptz NOT NULL DEFAULT now(),
      checksum    text
    )
  `);
}

async function appliedSet() {
  const res = await query('SELECT filename FROM schema_migrations');
  return new Set(res.rows.map((r) => r.filename));
}

export async function up() {
  await ensureMigrationsTable();
  const applied = await appliedSet();
  const files = (await fs.readdir(MIGRATIONS_DIR)).filter((f) => f.endsWith('.sql')).sort();

  for (const file of files) {
    if (applied.has(file)) {
      // eslint-disable-next-line no-console
      console.log(`[migrate] skip ${file}`);
      continue;
    }
    const sql = await fs.readFile(path.join(MIGRATIONS_DIR, file), 'utf8');
    // eslint-disable-next-line no-console
    console.log(`[migrate] applying ${file}`);
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      await client.query(sql);
      await client.query('INSERT INTO schema_migrations (filename) VALUES ($1)', [file]);
      await client.query('COMMIT');
    } catch (err) {
      await client.query('ROLLBACK');
      throw new Error(`Migration ${file} failed: ${err.message}`);
    } finally {
      client.release();
    }
  }
  // eslint-disable-next-line no-console
  console.log('[migrate] up to date');
}

export async function down() {
  // Safety: this drops every application object but keeps the database itself.
  await query(`
    DROP SCHEMA public CASCADE;
    CREATE SCHEMA public;
  `);
  // eslint-disable-next-line no-console
  console.log('[migrate] schema dropped');
}

const cmd = process.argv[2] || 'up';
try {
  if (cmd === 'down') await down();
  else await up();
  await pool.end();
  process.exit(0);
} catch (err) {
  // eslint-disable-next-line no-console
  console.error('[migrate] FAILED:', err.message);
  await pool.end().catch(() => {});
  process.exit(1);
}
