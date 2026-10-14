import request from 'supertest';

import { createApp } from '../../src/app.js';

/**
 * HTTP-level smoke tests.
 *
 * Why these and not full API tests: the suite must be runnable **without a
 * database**, so it exercises only the request pipeline that does not touch
 * PostgreSQL — health, the version endpoint, the 404 envelope, the auth guard on
 * a protected route, and request-id propagation. Endpoint behaviour that needs
 * real rows (auth lifecycle, sync, orders) belongs in database-backed tests and is
 * tracked in PROGRESS.md as not yet written.
 *
 * `createApp()` builds a fresh Express app; importing it has no import-time side
 * effects beyond reading config (see `src/config/index.js`).
 */
const app = createApp();

describe('API smoke tests (no database required)', () => {
  test('GET /health answers with a liveness envelope', async () => {
    const res = await request(app).get('/health');

    // 200 when the database is reachable, 503 when it is not. Both are valid
    // probe responses and must carry the same shape.
    expect([200, 503]).toContain(res.status);
    expect(res.body).toEqual(
      expect.objectContaining({
        status: expect.any(String),
        version: expect.any(String),
        time: expect.any(String),
      }),
    );
  });

  test('GET /api/v1/version reports the version', async () => {
    const res = await request(app).get('/api/v1/version');

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ version: '1.0.0', apiVersion: 'v1' });
  });

  test('an unknown API route returns the JSON error envelope (404)', async () => {
    const res = await request(app).get('/api/v1/this-route-does-not-exist');

    expect(res.status).toBe(404);
    expect(res.body.error).toBeDefined();
    expect(res.body.error.code).toBe('NOT_FOUND');
    expect(typeof res.body.error.message).toBe('string');
  });

  test('a protected endpoint rejects a request with no bearer token (401)', async () => {
    const res = await request(app).get('/api/v1/users/me');

    expect(res.status).toBe(401);
    expect(res.body.error).toBeDefined();
    expect(res.body.error.code).toBe('UNAUTHENTICATED');
  });

  test('the supplied request id is echoed back', async () => {
    const res = await request(app).get('/health').set('X-Request-Id', 'test-request-id');

    expect(res.headers['x-request-id']).toBe('test-request-id');
  });
});
