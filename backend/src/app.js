import express from 'express';
import helmet from 'helmet';
import cors from 'cors';
import compression from 'compression';
import { randomUUID } from 'node:crypto';
import swaggerUi from 'swagger-ui-express';
import YAML from 'yamljs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { config } from './config/index.js';
import { healthcheck } from './db/index.js';
import apiRouter from './modules/index.js';
import { notFoundHandler, errorHandler } from './middleware/errorHandler.js';
import { publicReadLimiter } from './middleware/rateLimit.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export function createApp() {
  const app = express();

  app.disable('x-powered-by');
  app.set('trust proxy', 1);

  // Correlate every request in logs and error envelopes.
  app.use((req, res, next) => {
    req.id = req.headers['x-request-id'] || randomUUID();
    res.set('X-Request-Id', req.id);
    next();
  });

  app.use(
    helmet({
      crossOriginResourcePolicy: { policy: 'cross-origin' },
      contentSecurityPolicy: config.isProd ? undefined : false,
      hsts: config.isProd ? { maxAge: 31_536_000, includeSubDomains: true, preload: true } : false,
    }),
  );

  const corsOptions = config.corsOrigins.length
    ? { origin: config.corsOrigins, credentials: false }
    : { origin: false };
  app.use(cors(corsOptions));

  app.use(compression());
  app.use(express.json({ limit: '2mb' }));
  app.use(express.urlencoded({ extended: true, limit: '2mb' }));

  // --- Health (unauthenticated) -------------------------------------------
  app.get('/health', async (_req, res) => {
    let db = false;
    try {
      db = await healthcheck();
    } catch {
      db = false;
    }
    res.status(db ? 200 : 503).json({
      status: db ? 'ok' : 'degraded',
      version: '1.0.0',
      time: new Date().toISOString(),
    });
  });

  // --- API documentation ---------------------------------------------------
  try {
    const spec = YAML.load(path.join(__dirname, '..', 'openapi.yaml'));
    app.use('/api-docs', swaggerUi.serve, swaggerUi.setup(spec, { customSiteTitle: 'AgriConnect API' }));
    app.get('/openapi.json', (_req, res) => res.json(spec));
  } catch {
    // Spec file not present in some environments; docs are optional at runtime.
  }

  // --- Versioned API -------------------------------------------------------
  app.use('/api/v1', publicReadLimiter, apiRouter);

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

export default createApp;
