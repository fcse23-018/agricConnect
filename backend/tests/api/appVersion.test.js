import request from 'supertest';

import { createApp } from '../../src/app.js';
import { normaliseManifest } from '../../src/modules/app/app.routes.js';

const app = createApp();

describe('GET /api/v1/app/version', () => {
  test('is reachable without a bearer token', async () => {
    const res = await request(app).get('/api/v1/app/version');
    expect(res.status).toBe(200);
    expect(res.body).toBeDefined();
    expect(typeof res.body.available).toBe('boolean');
  });

  test('reports honestly when no release is published yet', async () => {
    const res = await request(app).get('/api/v1/app/version');
    // The repository manifest ships with published:false and no download URL, so
    // the endpoint must not claim an update exists.
    if (res.body.available === false) {
      expect(typeof res.body.reason).toBe('string');
      expect(res.body.reason).toMatch(/no (new )?release|not published|disabled|unusable/i);
      expect(res.body.downloadUrl).toBeUndefined();
    } else {
      expect(res.body.latestVersionCode).toBeGreaterThan(0);
      expect(res.body.downloadUrl).toMatch(/^https:\/\//);
    }
  });

  test('never exposes a secret or an internal path', async () => {
    const res = await request(app).get('/api/v1/app/version');
    const body = JSON.stringify(res.body);
    expect(body).not.toMatch(/service_role/i);
    expect(body).not.toMatch(/private_key/i);
    expect(body).not.toMatch(/password/i);
    expect(body).not.toMatch(/[A-Za-z]:\\\\/); // no Windows filesystem paths
    expect(body).not.toMatch(/localhost/i);
  });
});

describe('normaliseManifest', () => {
  const good = {
    published: true,
    latestVersionName: '1.2.0',
    latestVersionCode: 4,
    minSupportedVersionCode: 2,
    downloadUrl: 'https://example.test/agriconnect-1.2.0.apk',
    packageName: 'bw.agriconnect',
    releaseNotes: ['Fixed plot mapping', 'Faster NDVI charts'],
    releasedAt: '2026-11-01',
  };

  test('accepts a well-formed, published manifest', () => {
    const result = normaliseManifest(good);
    expect(result.published).toBe(true);
    expect(result.latestVersionCode).toBe(4);
    expect(result.minSupportedVersionCode).toBe(2);
    expect(result.releaseNotes).toHaveLength(2);
  });

  test('treats an unpublished manifest as "no release", not an error', () => {
    const result = normaliseManifest({ ...good, published: false, downloadUrl: '' });
    expect(result.published).toBe(false);
    expect(result.downloadUrl).toBeNull();
  });

  test('treats a published manifest with no download URL as "no release"', () => {
    expect(normaliseManifest({ ...good, downloadUrl: '' }).published).toBe(false);
  });

  test('refuses a plain-HTTP download URL', () => {
    expect(() => normaliseManifest({ ...good, downloadUrl: 'http://example.test/app.apk' }))
      .toThrow(/HTTPS/);
  });

  test('refuses a manifest with no usable version code', () => {
    expect(() => normaliseManifest({ ...good, latestVersionCode: 'not-a-number' })).toThrow(/latestVersionCode/);
    expect(() => normaliseManifest({ ...good, latestVersionCode: 0 })).toThrow(/latestVersionCode/);
  });

  test('refuses a manifest with no version name', () => {
    expect(() => normaliseManifest({ ...good, latestVersionName: '' })).toThrow(/latestVersionName/);
  });

  test('clamps a minimum-supported code above the latest', () => {
    const result = normaliseManifest({ ...good, latestVersionCode: 5, minSupportedVersionCode: 9 });
    expect(result.minSupportedVersionCode).toBe(5);
  });

  test('defaults a missing minimum-supported code to 1', () => {
    const { minSupportedVersionCode, ...withoutMin } = good;
    expect(normaliseManifest(withoutMin).minSupportedVersionCode).toBe(1);
  });
});
