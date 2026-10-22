import { describe, it, expect, beforeEach, afterEach, jest } from '@jest/globals';
import { writeFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * Earth Engine credential resolution (`src/config/index.js`).
 *
 * Earth Engine refuses API keys ("API keys are not supported by this API"), so
 * the only workable credential is a service-account private key. The resolver
 * accepts it in three shapes — a mounted file, inline JSON, or raw env fields —
 * and these tests pin the property that matters: **a bad credential must never
 * crash the process at import time.** It must resolve to a plain `problem`
 * string, so the API starts and serves the labelled MOCK path instead of failing
 * to boot.
 *
 * `config/index.js` reads the environment at import time, so each case sets the
 * env, resets the module registry, and re-imports.
 */

let dir;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'gee-creds-'));
  jest.resetModules();
  delete process.env.GEE_KEY_FILE;
  delete process.env.GEE_KEY_JSON;
  delete process.env.GEE_PRIVATE_KEY;
  delete process.env.GEE_SERVICE_ACCOUNT_EMAIL;
  delete process.env.GEE_PROJECT_ID;
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
  delete process.env.GEE_KEY_FILE;
  delete process.env.GEE_KEY_JSON;
  delete process.env.GEE_PRIVATE_KEY;
  delete process.env.GEE_SERVICE_ACCOUNT_EMAIL;
  delete process.env.GEE_PROJECT_ID;
  jest.resetModules();
});

const writeKey = (name, content) => {
  const p = join(dir, name);
  writeFileSync(p, content, 'utf8');
  return p;
};

const VALID_KEY = JSON.stringify({
  type: 'service_account',
  project_id: 'agriconnect-512210',
  private_key: '-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7\n-----END PRIVATE KEY-----\n',
  client_email: 'agri@agriconnect-512210.iam.gserviceaccount.com',
});

async function loadConfig() {
  const mod = await import('../../src/config/index.js');
  return mod.config;
}

describe('nothing configured', () => {
  it('leaves Earth Engine off with no problem recorded', async () => {
    const config = await loadConfig();
    expect(config.gee.enabled).toBe(false);
    expect(config.gee.privateKey).toBe('');
    expect(config.gee.credentialProblem).toBeNull();
    expect(config.gee.credentialSource).toBeNull();
  });
});

describe('GEE_KEY_FILE', () => {
  it('reads a full service-account key and fills every field', async () => {
    process.env.GEE_KEY_FILE = writeKey('key.json', VALID_KEY);
    const config = await loadConfig();

    expect(config.gee.credentialSource).toBe('file');
    expect(config.gee.projectId).toBe('agriconnect-512210');
    expect(config.gee.serviceAccountEmail).toBe('agri@agriconnect-512210.iam.gserviceaccount.com');
    expect(config.gee.privateKey).toContain('BEGIN PRIVATE KEY');
    expect(config.gee.credentialProblem).toBeNull();
  });

  it('keeps explicit env values when the file omits them', async () => {
    process.env.GEE_KEY_FILE = writeKey(
      'partial.json',
      JSON.stringify({ type: 'service_account', private_key: '-----BEGIN PRIVATE KEY-----\nx\n' }),
    );
    process.env.GEE_PROJECT_ID = 'from-env';
    process.env.GEE_SERVICE_ACCOUNT_EMAIL = 'from-env@example.com';
    const config = await loadConfig();

    expect(config.gee.projectId).toBe('from-env');
    expect(config.gee.serviceAccountEmail).toBe('from-env@example.com');
  });

  it('reports a missing file rather than throwing', async () => {
    process.env.GEE_KEY_FILE = join(dir, 'nope.json');
    const config = await loadConfig();
    expect(config.gee.credentialProblem).toMatch(/could not be read/i);
    expect(config.gee.credentialProblem).toMatch(/ENOENT/);
  });

  it('reports malformed JSON rather than throwing', async () => {
    process.env.GEE_KEY_FILE = writeKey('bad.json', '{ this is not json');
    const config = await loadConfig();
    expect(config.gee.credentialProblem).toMatch(/not valid JSON/i);
  });

  it('rejects a non-service-account key by name', async () => {
    // The exact mistake a developer makes: downloading user OAuth credentials
    // instead of a service-account key.
    process.env.GEE_KEY_FILE = writeKey('user.json', JSON.stringify({ type: 'authorized_user', client_id: 'x' }));
    const config = await loadConfig();
    expect(config.gee.credentialProblem).toMatch(/not a service-account key/i);
    expect(config.gee.credentialProblem).toMatch(/authorized_user/);
  });
});

describe('GEE_KEY_JSON', () => {
  it('accepts an inline key', async () => {
    process.env.GEE_KEY_JSON = VALID_KEY;
    const config = await loadConfig();
    expect(config.gee.credentialSource).toBe('GEE_KEY_JSON');
    expect(config.gee.projectId).toBe('agriconnect-512210');
  });

  it('reports invalid JSON with the variable name', async () => {
    process.env.GEE_KEY_JSON = 'not json';
    const config = await loadConfig();
    expect(config.gee.credentialProblem).toMatch(/GEE_KEY_JSON/);
  });

  it('prefers the file when both are set', async () => {
    process.env.GEE_KEY_FILE = writeKey('key.json', VALID_KEY);
    process.env.GEE_KEY_JSON = 'not json';
    const config = await loadConfig();
    expect(config.gee.credentialSource).toBe('file');
    expect(config.gee.credentialProblem).toBeNull();
  });
});

describe('raw env fields (legacy)', () => {
  it('accepts an escaped-newline private key', async () => {
    process.env.GEE_PRIVATE_KEY = '-----BEGIN PRIVATE KEY-----\\nMIIabc\\n-----END PRIVATE KEY-----\\n';
    process.env.GEE_SERVICE_ACCOUNT_EMAIL = 'a@b.iam.gserviceaccount.com';
    process.env.GEE_PROJECT_ID = 'p';
    const config = await loadConfig();

    expect(config.gee.credentialSource).toBe('env');
    // The literal \n must become a real newline, or the PEM will not parse.
    expect(config.gee.privateKey).toContain('\n');
    expect(config.gee.privateKey).not.toContain('\\n');
  });
});

describe('production startup guard', () => {
  it('refuses to start when GEE_ENABLED is true but the key did not load', async () => {
    process.env.NODE_ENV = 'production';
    process.env.GEE_ENABLED = 'true';
    process.env.GEE_KEY_FILE = join(dir, 'missing.json');
    // Provide the other required production secrets so only GEE can fail.
    process.env.JWT_ACCESS_SECRET = 'a'.repeat(64);
    process.env.JWT_REFRESH_SECRET = 'b'.repeat(64);
    process.env.EXPOSE_DEV_CODES = 'false';
    process.env.ALLOW_DELIVERY_GAP = 'true';
    process.env.UPLOAD_DRIVER = 'local';

    jest.resetModules();
    const mod = await import('../../src/config/index.js');
    const exitSpy = jest.spyOn(process, 'exit').mockImplementation(() => { throw new Error('EXIT'); });
    const errSpy = jest.spyOn(console, 'error').mockImplementation(() => {});

    expect(() => mod.assertProdSecrets()).toThrow('EXIT');
    const printed = errSpy.mock.calls.flat().join(' ');
    expect(printed).toMatch(/service-account key could not be loaded/i);

    exitSpy.mockRestore();
    errSpy.mockRestore();
    delete process.env.NODE_ENV;
    delete process.env.GEE_ENABLED;
    delete process.env.JWT_ACCESS_SECRET;
    delete process.env.JWT_REFRESH_SECRET;
    delete process.env.EXPOSE_DEV_CODES;
    delete process.env.ALLOW_DELIVERY_GAP;
    delete process.env.UPLOAD_DRIVER;
  });
});
