import { describe, it, expect, jest, beforeEach } from '@jest/globals';

/**
 * `authenticate` re-loads the caller from the database on every request, so the
 * request context it builds (`req.user`) is the only identity downstream routes
 * get. It must therefore carry the same facts the users table holds — including
 * `fullName`, which the herd summary renders for the farmer. When it was missing,
 * `GET /farm/herd/summary` returned `"fullName": undefined` and JSON dropped the
 * field entirely.
 *
 * The db module is mocked because `src/db/index.js` creates a real pool at import
 * time; the JWT is mocked so the test does not depend on the signing secret.
 */

const one = jest.fn();

jest.unstable_mockModule('../../src/db/index.js', () => ({
  one,
  many: jest.fn(),
  query: jest.fn(),
  withTransaction: jest.fn(),
  healthcheck: jest.fn(),
  pool: { connect: jest.fn(), query: jest.fn(), on: jest.fn(), end: jest.fn() },
  default: {},
}));

jest.unstable_mockModule('../../src/utils/jwt.js', () => ({
  verifyAccessToken: jest.fn(() => ({ sub: 'user-1', typ: 'access' })),
  signAccessToken: jest.fn(),
  accessTokenTtlSeconds: jest.fn(() => 900),
  default: {},
}));

const { authenticate } = await import('../../src/middleware/auth.js');
const { ErrorCode } = await import('../../src/utils/errors.js');

const req = () => ({ headers: { authorization: 'Bearer token' } });

/** Runs the middleware and resolves with whatever it passed to `next`. */
function run(middleware, request) {
  return new Promise((resolve) => {
    middleware(request, {}, resolve);
  });
}

beforeEach(() => {
  one.mockReset();
});

describe('authenticate', () => {
  it('builds req.user with the name, role and status from the database', async () => {
    one.mockResolvedValue({
      id: 'user-1',
      full_name: 'Neo Mokoena',
      role: 'FARM_OWNER',
      account_status: 'VERIFIED',
      deleted_at: null,
    });

    const request = req();
    expect(await run(authenticate, request)).toBeUndefined();

    expect(one.mock.calls[0][0]).toContain('full_name');
    expect(request.user).toEqual({
      id: 'user-1',
      fullName: 'Neo Mokoena',
      role: 'FARM_OWNER',
      accountStatus: 'VERIFIED',
    });
  });

  it('uses the database role, not the one in the token', async () => {
    one.mockResolvedValue({
      id: 'user-1',
      full_name: 'Neo Mokoena',
      role: 'FARM_OWNER',
      account_status: 'VERIFIED',
      deleted_at: null,
    });

    const request = req();
    await run(authenticate, request);

    expect(request.user.role).toBe('FARM_OWNER');
  });

  it('refuses a suspended account', async () => {
    one.mockResolvedValue({
      id: 'user-1',
      full_name: 'Neo Mokoena',
      role: 'FARMER',
      account_status: 'SUSPENDED',
      deleted_at: null,
    });

    const request = req();
    const error = await run(authenticate, request);

    expect(error?.code).toBe(ErrorCode.ACCOUNT_SUSPENDED);
    expect(request.user).toBeUndefined();
  });

  it('refuses a deleted account', async () => {
    one.mockResolvedValue({
      id: 'user-1',
      full_name: 'Neo Mokoena',
      role: 'FARMER',
      account_status: 'DELETED',
      deleted_at: '2026-01-01T00:00:00.000Z',
    });

    const request = req();
    const error = await run(authenticate, request);

    expect(error?.code).toBe(ErrorCode.UNAUTHENTICATED);
    expect(request.user).toBeUndefined();
  });
});
