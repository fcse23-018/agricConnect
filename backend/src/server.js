import { createApp } from './app.js';
import { config, assertProdSecrets } from './config/index.js';
import { pool, healthcheck } from './db/index.js';

assertProdSecrets();

const app = createApp();

const server = app.listen(config.port, async () => {
  let dbOk = false;
  try {
    dbOk = await healthcheck();
  } catch {
    dbOk = false;
  }
  // eslint-disable-next-line no-console
  console.log(
    `[agriconnect] listening on :${config.port}  env=${config.env}  db=${dbOk ? 'ok' : 'UNAVAILABLE'}  delivery=${config.delivery.provider}  gee=${config.gee.enabled ? 'on' : 'mock'}`,
  );
  if (!dbOk) {
    // eslint-disable-next-line no-console
    console.warn('[agriconnect] PostgreSQL is not reachable. Run `npm run migrate` once it is up.');
  }
});

function shutdown(signal) {
  // eslint-disable-next-line no-console
  console.log(`[agriconnect] ${signal} received, shutting down…`);
  server.close(async () => {
    await pool.end().catch(() => {});
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));

export default server;
