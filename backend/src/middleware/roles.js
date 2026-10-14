import { forbidden, unauthorized } from '../utils/errors.js';

export const ROLES = Object.freeze({ FARMER: 'FARMER', FARM_EMPLOYEE: 'FARM_EMPLOYEE', BUYER: 'BUYER' });

/**
 * Role-based authorization. Must run after `authenticate`.
 * Mirrors `authorizeRoles(...allowedRoles)` from the documented implementation plan.
 */
export function authorizeRoles(...allowedRoles) {
  return (req, _res, next) => {
    if (!req.user) return next(unauthorized());
    if (!allowedRoles.includes(req.user.role)) {
      return next(forbidden('Access denied: your account does not have permission for this operation.'));
    }
    next();
  };
}

/** Convenience guards matching the documented permission matrix. */
export const farmerOnly = authorizeRoles(ROLES.FARMER);
export const farmerOrEmployee = authorizeRoles(ROLES.FARMER, ROLES.FARM_EMPLOYEE);
export const buyerOnly = authorizeRoles(ROLES.BUYER);
export const buyerOrFarmer = authorizeRoles(ROLES.BUYER, ROLES.FARMER);

/** Pure predicate used by both routes and the sync engine. */
export function canAccessFinancials(role) {
  return role === ROLES.FARMER;
}
export function canRecordFieldData(role) {
  return role === ROLES.FARMER || role === ROLES.FARM_EMPLOYEE;
}
export function canManageMarketplace(role) {
  return role === ROLES.FARMER;
}
export function canBuy(role) {
  return role === ROLES.BUYER;
}

export default { authorizeRoles, farmerOnly, farmerOrEmployee, buyerOnly, buyerOrFarmer, ROLES };
