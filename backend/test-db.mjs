import { query, pool } from "./src/db/index.js";

try {
  const result = await query(`
    SELECT
      current_database() AS database_name,
      to_regclass('public.schema_migrations') AS migration_table
  `);

  console.log("Database check:", result.rows[0]);

  const migrations = await query(`
    SELECT filename
    FROM public.schema_migrations
    ORDER BY filename
  `);

  console.log("Recorded migrations:", migrations.rows);
} catch (error) {
  console.error("Database check failed:", error);
  process.exitCode = 1;
} finally {
  await pool.end();
}
