import { describe, it, expect, beforeEach, jest } from '@jest/globals';

/**
 * Verification-code delivery honesty.
 *
 * Two rules are protected here:
 *
 *  1. The server never claims a code was sent when the transport refused it. A
 *     Twilio trial account can only message numbers verified in Twilio (error
 *     21608); the app must be able to say "no SMS was delivered" and why.
 *  2. That delivery detail is only returned to the signed-in owner of the
 *     account. Anyone else gets the uniform acknowledgement, so the endpoint
 *     cannot be used to discover whether a phone number is registered.
 *
 * The database and the delivery transport are mocked; no network or Postgres.
 */

const delivered = { value: { delivered: false, reason: 'TRIAL_UNVERIFIED_NUMBER' } };
const userRow = {
  id: 'u1',
  account_status: 'UNVERIFIED',
  verified_at: null,
  deleted_at: null,
};

jest.unstable_mockModule('../../src/db/index.js', () => ({
  query: async () => ({ rows: [] }),
  one: async () => userRow,
  many: async () => [],
  withTransaction: async (fn) => fn({ query: async () => ({ rows: [] }) }),
}));

jest.unstable_mockModule('../../src/services/delivery.js', () => ({
  sendVerificationCode: async () => delivered.value,
  sendPasswordResetCode: async () => ({ delivered: false }),
}));

const { requestVerificationCode } = await import('../../src/modules/auth/auth.service.js');

const OWNER = { user: { id: 'u1' }, ip: '127.0.0.1', headers: {} };
const STRANGER = { user: { id: 'u2' }, ip: '127.0.0.1', headers: {} };
const ANONYMOUS = { ip: '127.0.0.1', headers: {} };

describe('verification delivery reporting', () => {
  beforeEach(() => {
    delivered.value = { delivered: false, reason: 'TRIAL_UNVERIFIED_NUMBER' };
  });

  it('tells the signed-in owner that the SMS was not delivered, with a fixed reason', async () => {
    const result = await requestVerificationCode('+26771234567', OWNER);
    expect(result.delivery).toEqual({
      channel: 'SMS',
      delivered: false,
      reason: 'TRIAL_UNVERIFIED_NUMBER',
    });
    expect(result.expiresInMinutes).toBeGreaterThan(0);
  });

  it('reports success only when the transport actually delivered', async () => {
    delivered.value = { delivered: true };
    const result = await requestVerificationCode('+26771234567', OWNER);
    expect(result.delivery).toEqual({ channel: 'SMS', delivered: true, reason: null });
  });

  it('never reveals delivery status to a different signed-in user', async () => {
    const result = await requestVerificationCode('+26771234567', STRANGER);
    expect(result.delivery).toBeUndefined();
    expect(result.message).toMatch(/if an account matches/i);
  });

  it('never reveals delivery status to an anonymous caller', async () => {
    const result = await requestVerificationCode('+26771234567', ANONYMOUS);
    expect(result.delivery).toBeUndefined();
  });

  it('keeps the acknowledgement identical for owner and stranger', async () => {
    const owner = await requestVerificationCode('+26771234567', OWNER);
    const stranger = await requestVerificationCode('+26771234567', STRANGER);
    expect(owner.message).toBe(stranger.message);
  });
});
