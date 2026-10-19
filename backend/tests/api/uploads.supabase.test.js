import { describe, it, expect, beforeAll } from '@jest/globals';
import request from 'supertest';

/**
 * The `/uploads` route behaves differently per storage driver, and the driver is
 * read from env **once at import time** (see `src/config/index.js`). Jest gives
 * each test file its own module registry, so this file sets the supabase driver
 * *before* importing the app, and is therefore the only place the redirect path
 * is covered. The complementary local-disk behaviour is covered by `smoke.test.js`.
 *
 * No network access happens here: the handler validates the name and builds a
 * redirect target from server config. It never contacts Supabase.
 */
process.env.UPLOAD_DRIVER = 'supabase';
process.env.SUPABASE_URL = 'https://example-project.supabase.co';
process.env.SUPABASE_SERVICE_ROLE_KEY = 'test-service-role-key';
process.env.SUPABASE_STORAGE_BUCKET = 'test-bucket';

const { createApp } = await import('../../src/app.js');
const { publicObjectUrl } = await import('../../src/services/uploads.js');

const app = createApp();

const VALID_UUID = '9f8e7d6c-1234-4abc-9def-000000000000';

describe('GET /uploads under the supabase driver', () => {
  it('302-redirects a name we generated to the object public URL', async () => {
    const res = await request(app).get(`/uploads/avatar-${VALID_UUID}.jpg`);

    expect(res.status).toBe(302);
    expect(res.headers.location).toBe(
      `https://example-project.supabase.co/storage/v1/object/public/test-bucket/avatar-${VALID_UUID}.jpg`,
    );
  });

  it('redirects every accepted extension', async () => {
    for (const ext of ['jpg', 'png', 'webp']) {
      const res = await request(app).get(`/uploads/image-${VALID_UUID}.${ext}`);
      expect(res.status).toBe(302);
      expect(res.headers.location).toContain(`.${ext}`);
    }
  });

  it('agrees with publicObjectUrl, the helper the redirect is built from', async () => {
    const res = await request(app).get(`/uploads/image-${VALID_UUID}.webp`);
    expect(res.headers.location).toBe(publicObjectUrl(`image-${VALID_UUID}.webp`));
  });

  it('refuses a path traversal and falls through to the JSON 404', async () => {
    const res = await request(app).get('/uploads/..%2f..%2fetc%2fpasswd');

    expect(res.status).toBe(404);
    expect(res.body.error).toBeDefined();
    expect(res.body.error.code).toBe('NOT_FOUND');
    expect(res.headers.location).toBeUndefined();
  });

  it('refuses an extension that is not in the allowlist', async () => {
    const res = await request(app).get(`/uploads/image-${VALID_UUID}.php`);

    expect(res.status).toBe(404);
    expect(res.headers.location).toBeUndefined();
  });

  it('refuses a filename we would never have generated', async () => {
    const res = await request(app).get('/uploads/not-a-uuid.jpg');

    expect(res.status).toBe(404);
    expect(res.headers.location).toBeUndefined();
  });

  it('refuses an absolute URL in the path, so this cannot be an open redirect', async () => {
    const res = await request(app).get('/uploads/https%3a%2f%2fevil.example%2fx.jpg');

    expect(res.status).toBe(404);
    expect(res.headers.location).toBeUndefined();
  });

  it('refuses a prefixed name that is not uuid-shaped', async () => {
    const res = await request(app).get('/uploads/avatar-12345.jpg');

    expect(res.status).toBe(404);
    expect(res.headers.location).toBeUndefined();
  });
});
