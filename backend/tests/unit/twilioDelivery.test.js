import { describe, it, expect, beforeEach, afterEach, jest } from '@jest/globals';

/**
 * Twilio SMS delivery.
 *
 * The behaviour these tests protect is **honesty about sending**: a code that was
 * never delivered must never be recorded as delivered. The password-reset flow
 * trusts `delivered`, so a swallowed Twilio error would tell a farmer "we sent
 * your code" when nothing left the server — precisely the fabricated recovery the
 * project forbids.
 *
 * Both the database and `fetch` are mocked, so no test touches the network or a
 * real database.
 */

// The outbox write is not what is under test; stub it so no Postgres is needed.
const recorded = [];
jest.unstable_mockModule('../../src/db/index.js', () => ({
  query: async (text, params) => {
    recorded.push({ text, params });
    return { rows: [] };
  },
  one: async () => null,
  many: async () => [],
}));

const { config } = await import('../../src/config/index.js');
const { deliver } = await import('../../src/services/delivery.js');

/** A minimal `Response`-like object. */
function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return {
    ok,
    status,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
  };
}

const SAVED = {};
let calls = [];

beforeEach(() => {
  calls = [];
  recorded.length = 0;

  SAVED.provider = config.delivery.provider;
  SAVED.twilio = { ...config.delivery.twilio };
  SAVED.exposeDevCodes = config.delivery.exposeDevCodes;

  config.delivery.twilio.accountSid = 'ACtest0000000000000000000000000000';
  config.delivery.twilio.authToken = 'test-auth-token';
  config.delivery.twilio.from = '+15550001111';
  config.delivery.exposeDevCodes = false;

  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return jsonResponse({ sid: 'SM00000000000000000000000000000000', status: 'queued' });
  };
});

afterEach(() => {
  config.delivery.provider = SAVED.provider;
  config.delivery.twilio = SAVED.twilio;
  config.delivery.exposeDevCodes = SAVED.exposeDevCodes;
});

/** The row `deliver()` wrote to the outbox, if any. */
function outboxRow() {
  return recorded.find((r) => /INSERT INTO message_outbox/i.test(r.text));
}

describe('Twilio delivery configuration guards', () => {
  it('reports incomplete credentials rather than pretending to send', async () => {
    config.delivery.provider = 'TWILIO';
    config.delivery.twilio.authToken = '';

    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'hello' });

    expect(res.delivered).toBe(false);
    expect(res.error).toMatch(/incomplete/i);
    // Nothing was sent…
    expect(calls.length).toBe(0);
    // …and the outbox records the failure rather than a delivery.
    expect(outboxRow()).toBeDefined();
    expect(outboxRow().params).toContain(false);
  });
});

describe('Twilio request shape', () => {
  it('sends a Basic-auth POST to the account Messages endpoint with a form body', async () => {
    config.delivery.provider = 'TWILIO';
    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'Your code is 123456' });

    expect(res.delivered).toBe(true);
    expect(calls.length).toBe(1);
    const { url, options } = calls[0];

    expect(url).toBe(
      'https://api.twilio.com/2010-04-01/Accounts/ACtest0000000000000000000000000000/Messages.json',
    );
    expect(options.method).toBe('POST');
    expect(options.headers['Content-Type']).toBe('application/x-www-form-urlencoded');
    expect(options.headers.Authorization).toBe(
      `Basic ${Buffer.from('ACtest0000000000000000000000000000:test-auth-token').toString('base64')}`,
    );

    const form = new URLSearchParams(options.body);
    expect(form.get('To')).toBe('+26771234567');
    expect(form.get('From')).toBe('+15550001111');
    expect(form.get('Body')).toBe('Your code is 123456');
  });

  it('records Twilio\u2019s message SID for tracing, and never puts the token in the URL', async () => {
    config.delivery.provider = 'TWILIO';
    await deliver({ channel: 'SMS', destination: '+26771234567', body: 'x' });

    for (const c of calls) expect(c.url).not.toContain('test-auth-token');

    // provider_ref is the 7th bind parameter of the outbox INSERT.
    const row = outboxRow();
    expect(row.params).toContain('SM00000000000000000000000000000000');
  });
});

describe('Twilio rejections are reported, never swallowed', () => {
  it('records a non-OK response as not delivered, with Twilio\u2019s own reason', async () => {
    config.delivery.provider = 'TWILIO';
    globalThis.fetch = async () =>
      jsonResponse({ code: 21608, message: 'The number +26771234567 is unverified.' }, { ok: false, status: 400 });

    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'x' });

    expect(res.delivered).toBe(false);
    expect(res.error).toMatch(/HTTP 400/);
    expect(res.error).toMatch(/unverified/i);
  });

  it('treats a transport failure as not delivered', async () => {
    config.delivery.provider = 'TWILIO';
    globalThis.fetch = async () => {
      throw new Error('getaddrinfo ENOTFOUND api.twilio.com');
    };

    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'x' });

    expect(res.delivered).toBe(false);
    expect(res.error).toMatch(/Twilio request failed/i);
  });

  it('handles a non-JSON error body without crashing', async () => {
    config.delivery.provider = 'TWILIO';
    globalThis.fetch = async () => jsonResponse('<html>502 Bad Gateway</html>', { ok: false, status: 502 });

    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'x' });

    expect(res.delivered).toBe(false);
    expect(res.error).toMatch(/HTTP 502/);
  });

  it('reports a timeout rather than hanging forever', async () => {
    config.delivery.provider = 'TWILIO';
    globalThis.fetch = async () => {
      const err = new Error('aborted');
      err.name = 'AbortError';
      throw err;
    };

    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'x' });

    expect(res.delivered).toBe(false);
    expect(res.error).toMatch(/timed out/i);
  });
});

describe('non-Twilio providers are unaffected', () => {
  it('NONE still records as OUTBOX and does not call Twilio', async () => {
    config.delivery.provider = 'NONE';
    const res = await deliver({ channel: 'SMS', destination: '+26771234567', body: 'x' });

    expect(res.delivered).toBe(false);
    expect(res.provider).toBe('OUTBOX');
    expect(calls.length).toBe(0);
  });

  it('TWILIO with an EMAIL channel is not sent as SMS', async () => {
    config.delivery.provider = 'TWILIO';
    const res = await deliver({ channel: 'EMAIL', destination: 'a@b.com', subject: 's', body: 'x' });

    expect(res.delivered).toBe(false);
    expect(calls.length).toBe(0);
  });
});
