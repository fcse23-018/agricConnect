import { describe, it, expect } from '@jest/globals';

import {
  authorizeRoles,
  canAccessFinancials,
  canRecordFieldData,
  canBuy,
  canManageMarketplace,
  farmerOnly,
  farmerOrEmployee,
  buyerOnly,
  buyerOrFarmer,
  ROLES,
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

describe('authorizeRoles — server-side RBAC is the real enforcement point', () => {
  it('allows a permitted role', () => {
    expect(run(farmerOnly, ROLES.FARMER)).toBeUndefined();
  });

  it('rejects a role without permission with a 403 FORBIDDEN', () => {
    const err = run(farmerOnly, ROLES.FARM_EMPLOYEE);
    expect(err).toBeDefined();
    expect(err.status).toBe(403);
    expect(err.code).toBe('FORBIDDEN');
  });

  it('rejects a buyer from farmer-only operations', () => {
    const err = run(farmerOnly, ROLES.BUYER);
    expect(err.status).toBe(403);
  });

  it('demands authentication before authorization', () => {
    const err = run(farmerOnly, undefined);
    expect(err).toBeDefined();
    expect(err.status).toBe(401);
  });

  it('farmerOrEmployee admits both, but not buyers', () => {
    expect(run(farmerOrEmployee, ROLES.FARMER)).toBeUndefined();
    expect(run(farmerOrEmployee, ROLES.FARM_EMPLOYEE)).toBeUndefined();
    expect(run(farmerOrEmployee, ROLES.BUYER).status).toBe(403);
  });

  it('buyerOnly admits buyers only', () => {
    expect(run(buyerOnly, ROLES.BUYER)).toBeUndefined();
    expect(run(buyerOnly, ROLES.FARMER).status).toBe(403);
  });

  it('buyerOrFarmer admits buyers and farmers, but not employees', () => {
    expect(run(buyerOrFarmer, ROLES.BUYER)).toBeUndefined();
    expect(run(buyerOrFarmer, ROLES.FARMER)).toBeUndefined();
    expect(run(buyerOrFarmer, ROLES.FARM_EMPLOYEE).status).toBe(403);
  });

  it('is not fooled by an unknown/tampered role string', () => {
    expect(run(farmerOnly, 'ADMIN').status).toBe(403);
    expect(run(farmerOnly, 'farmer').status).toBe(403); // case-sensitive
  });
});

describe('permission predicates mirror the documented matrix', () => {
  it('only a farmer may access financials (employees explicitly excluded)', () => {
    expect(canAccessFinancials(ROLES.FARMER)).toBe(true);
    expect(canAccessFinancials(ROLES.FARM_EMPLOYEE)).toBe(false);
    expect(canAccessFinancials(ROLES.BUYER)).toBe(false);
  });

  it('farmers and employees may record field data; buyers may not', () => {
    expect(canRecordFieldData(ROLES.FARMER)).toBe(true);
    expect(canRecordFieldData(ROLES.FARM_EMPLOYEE)).toBe(true);
    expect(canRecordFieldData(ROLES.BUYER)).toBe(false);
  });

  it('only farmers manage marketplace listings', () => {
    expect(canManageMarketplace(ROLES.FARMER)).toBe(true);
    expect(canManageMarketplace(ROLES.FARM_EMPLOYEE)).toBe(false);
    expect(canManageMarketplace(ROLES.BUYER)).toBe(false);
  });

  it('only buyers place orders', () => {
    expect(canBuy(ROLES.BUYER)).toBe(true);
    expect(canBuy(ROLES.FARMER)).toBe(false);
    expect(canBuy(ROLES.FARM_EMPLOYEE)).toBe(false);
  });
});
