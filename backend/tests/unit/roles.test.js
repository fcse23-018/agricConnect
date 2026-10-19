import { describe, it, expect } from '@jest/globals';

import {
  authorizeRoles,
  canAccessFinancials,
  canRecordFieldData,
  canBuy,
  canComputeNdvi,
  canManageMarketplace,
  canViewPlots,
  farmOwnerOnly,
  farmOwnerOrFarmer,
  buyerOrFarmOwner,
  anyRole,
  ROLES,
  ALL_ROLES,
} from '../../src/middleware/roles.js';

/**
 * A tiny express-like harness: invoke the middleware with a fake request carrying
 * a role, and capture what it passes to `next`.
 */
function run(middleware, role) {
  const req = role === undefined ? {} : { user: { id: 'u1', role } };
  let captured;
  middleware(req, {}, (err) => {
    captured = err;
  });
  return captured; // undefined == passed through
}

describe('the role set', () => {
  it('is exactly farm owner, farmer and buyer', () => {
    expect(Object.values(ROLES).sort()).toEqual(['BUYER', 'FARMER', 'FARM_OWNER']);
    expect([...ALL_ROLES].sort()).toEqual(['BUYER', 'FARMER', 'FARM_OWNER']);
  });

  it('retires the old FARM_EMPLOYEE label', () => {
    expect(Object.values(ROLES)).not.toContain('FARM_EMPLOYEE');
  });
});

describe('authorizeRoles — server-side RBAC is the real enforcement point', () => {
  it('allows a permitted role', () => {
    expect(run(farmOwnerOnly, ROLES.FARM_OWNER)).toBeUndefined();
  });

  it('rejects a role without permission with a 403 FORBIDDEN', () => {
    const err = run(farmOwnerOnly, ROLES.FARMER);
    expect(err).toBeDefined();
    expect(err.status).toBe(403);
    expect(err.code).toBe('FORBIDDEN');
  });

  it('rejects a buyer from farm-owner-only operations', () => {
    expect(run(farmOwnerOnly, ROLES.BUYER).status).toBe(403);
  });

  it('demands authentication before authorization', () => {
    const err = run(farmOwnerOnly, undefined);
    expect(err).toBeDefined();
    expect(err.status).toBe(401);
  });

  it('ad hoc guards work too (authorizeRoles is the primitive)', () => {
    expect(run(authorizeRoles(ROLES.BUYER), ROLES.BUYER)).toBeUndefined();
    expect(run(authorizeRoles(ROLES.BUYER), ROLES.FARMER).status).toBe(403);
  });

  it('farmOwnerOrFarmer admits the owner and the farmer who works the land, not buyers', () => {
    expect(run(farmOwnerOrFarmer, ROLES.FARM_OWNER)).toBeUndefined();
    expect(run(farmOwnerOrFarmer, ROLES.FARMER)).toBeUndefined();
    expect(run(farmOwnerOrFarmer, ROLES.BUYER).status).toBe(403);
  });

  it('buyerOrFarmOwner admits buyers and owners, but not the working farmer', () => {
    expect(run(buyerOrFarmOwner, ROLES.BUYER)).toBeUndefined();
    expect(run(buyerOrFarmOwner, ROLES.FARM_OWNER)).toBeUndefined();
    expect(run(buyerOrFarmOwner, ROLES.FARMER).status).toBe(403);
  });

  it('anyRole admits all three — buying and browsing are not a role', () => {
    for (const role of ALL_ROLES) expect(run(anyRole, role)).toBeUndefined();
  });

  it('is not fooled by an unknown, retired or mis-cased role string', () => {
    expect(run(anyRole, 'ADMIN').status).toBe(403);
    expect(run(farmOwnerOnly, 'FARM_EMPLOYEE').status).toBe(403); // retired label
    expect(run(farmOwnerOnly, 'farm_owner').status).toBe(403); // case-sensitive
  });
});

describe('permission predicates mirror the matrix', () => {
  it('only the farm owner may access financials', () => {
    expect(canAccessFinancials(ROLES.FARM_OWNER)).toBe(true);
    expect(canAccessFinancials(ROLES.FARMER)).toBe(false);
    expect(canAccessFinancials(ROLES.BUYER)).toBe(false);
  });

  it('the owner and the farmer record field data; buyers do not', () => {
    expect(canRecordFieldData(ROLES.FARM_OWNER)).toBe(true);
    expect(canRecordFieldData(ROLES.FARMER)).toBe(true);
    expect(canRecordFieldData(ROLES.BUYER)).toBe(false);
  });

  it('only the farm owner manages marketplace listings', () => {
    expect(canManageMarketplace(ROLES.FARM_OWNER)).toBe(true);
    expect(canManageMarketplace(ROLES.FARMER)).toBe(false);
    expect(canManageMarketplace(ROLES.BUYER)).toBe(false);
  });

  it('the owner and the farmer who works the land may view plots', () => {
    expect(canViewPlots(ROLES.FARM_OWNER)).toBe(true);
    expect(canViewPlots(ROLES.FARMER)).toBe(true);
    expect(canViewPlots(ROLES.BUYER)).toBe(false);
  });

  it('only the farm owner computes NDVI', () => {
    expect(canComputeNdvi(ROLES.FARM_OWNER)).toBe(true);
    expect(canComputeNdvi(ROLES.FARMER)).toBe(false);
    expect(canComputeNdvi(ROLES.BUYER)).toBe(false);
  });

  it('every role can buy — that is the point of "they can all buy"', () => {
    expect(canBuy(ROLES.FARM_OWNER)).toBe(true);
    expect(canBuy(ROLES.FARMER)).toBe(true);
    expect(canBuy(ROLES.BUYER)).toBe(true);
    expect(canBuy('ADMIN')).toBe(false);
  });
});
