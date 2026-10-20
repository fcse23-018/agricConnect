import { describe, it, expect, jest, beforeEach } from '@jest/globals';

/**
 * Farm/plot ownership resolution — the rule that decides *which farm's* data a
 * caller may see.
 *
 * Both farm management and satellite monitoring resolve the owner's farmer id
 * through this one function, so it is the single place where "a FARMER reads the
 * farm that employs them" is enforced. If it regressed, an employed farmer would
 * silently read their own (empty) id and see no plots — or, worse, a caller
 * linked to nothing would be handed a farm. It queries the database, so the db
 * module is mocked: this suite runs with no PostgreSQL.
 *
 * The mock is installed with `unstable_mockModule` *before* the module under
 * test is imported, because `src/db/index.js` builds a connection pool at import
 * time and would otherwise reach for a real database.
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

const { resolveOwnerFarmerId } = await import('../../src/modules/farm/records.service.js');
const { ROLES } = await import('../../src/middleware/roles.js');

beforeEach(() => {
  one.mockReset();
});

describe('resolveOwnerFarmerId', () => {
  it('gives a farm owner their own id, without querying the database', async () => {
    const owner = { id: 'owner-1', role: ROLES.FARM_OWNER };

    await expect(resolveOwnerFarmerId(owner)).resolves.toBe('owner-1');
    expect(one).not.toHaveBeenCalled();
  });

  it('gives an employed farmer the employer’s id, so they read the same farm', async () => {
    one.mockResolvedValue({ employer_farmer_id: 'owner-1' });
    const worker = { id: 'worker-9', role: ROLES.FARMER };

    await expect(resolveOwnerFarmerId(worker)).resolves.toBe('owner-1');
    // The lookup is by the caller's own id — never a client-supplied farm id.
    expect(one).toHaveBeenCalledWith(expect.stringContaining('employer_farmer_id'), ['worker-9']);
  });

  it('refuses an unlinked farmer with an actionable 403 rather than an empty farm', async () => {
    one.mockResolvedValue({ employer_farmer_id: null });
    const worker = { id: 'worker-9', role: ROLES.FARMER };

    await expect(resolveOwnerFarmerId(worker)).rejects.toMatchObject({ status: 403 });
  });

  it('refuses a farmer whose row cannot be found at all', async () => {
    one.mockResolvedValue(null);

    await expect(resolveOwnerFarmerId({ id: 'ghost', role: ROLES.FARMER }))
      .rejects.toMatchObject({ status: 403 });
  });

  it('refuses an unauthenticated caller', async () => {
    await expect(resolveOwnerFarmerId(null)).rejects.toMatchObject({ status: 403 });
  });

  it('never returns another farmer’s id for a buyer', async () => {
    // A buyer has no farm; if they were ever routed here they must be refused,
    // not handed an owner id from the employer lookup.
    one.mockResolvedValue({ employer_farmer_id: 'owner-1' });

    await expect(resolveOwnerFarmerId({ id: 'buyer-3', role: ROLES.BUYER }))
      .rejects.toMatchObject({ status: 403 });
  });
});
