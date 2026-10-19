import { forbidden, unauthorized } from '../utils/errors.js';

/**
 * The three account roles.
 *
 *  - **FARM_OWNER** — owns the farm. Maps plots, sees financials, computes NDVI,
 *    sells produce on the marketplace and employs farmers.
 *  - **FARMER** — works the land. Records harvests and livestock against the farm of
 *    the owner who employs them.
 *  - **BUYER** — buys produce and tracks orders.
 *
 * **Every role may buy.** Agriculture is a two-sided market: the person selling maize
 * this week is buying seed or feed next week, so buying is not a role — it is a
 * capability all signed-in accounts have.
 *
 * These names supersede the old `FARMER` / `FARM_EMPLOYEE` pair. The old `FARMER`
 * (the account that owned the farm) is now `FARM_OWNER`; the old `FARM_EMPLOYEE` (the
 * worker) is now `FARMER`. `migration 006_role_rename.sql` renames the database enum
 * in place, so existing rows keep their capabilities and only their label changes.
 */
export const ROLES = Object.freeze({
  FARM_OWNER: 'FARM_OWNER',
  FARMER: 'FARMER',
  BUYER: 'BUYER',
});

/** Every role, for the routes anyone signed in may use. */
export const ALL_ROLES = Object.freeze([ROLES.FARM_OWNER, ROLES.FARMER, ROLES.BUYER]);

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

/** Convenience guards matching the permission matrix. */
export const farmOwnerOnly = authorizeRoles(ROLES.FARM_OWNER);
export const farmOwnerOrFarmer = authorizeRoles(ROLES.FARM_OWNER, ROLES.FARMER);
export const buyerOrFarmOwner = authorizeRoles(ROLES.BUYER, ROLES.FARM_OWNER);
/**
 * Anyone signed in. Used for buying and for browsing the marketplace, which all three
 * roles may do. Written out rather than left to `authenticate` alone so the permission
 * is visible at the route and a future role cannot silently inherit it.
 */
export const anyRole = authorizeRoles(...ALL_ROLES);

/** Pure predicates used by both routes and the sync engine. */
export function canAccessFinancials(role) {
  return role === ROLES.FARM_OWNER;
}
export function canRecordFieldData(role) {
  return role === ROLES.FARM_OWNER || role === ROLES.FARMER;
}
/** Selling: only the account that owns the farm may list and manage produce. */
export function canManageMarketplace(role) {
  return role === ROLES.FARM_OWNER;
}
export function canComputeNdvi(role) {
  return role === ROLES.FARM_OWNER;
}
/** Viewing plots: the owner, and the farmer who works them. */
export function canViewPlots(role) {
  return role === ROLES.FARM_OWNER || role === ROLES.FARMER;
}
/** Buying: every role. */
export function canBuy(role) {
  return ALL_ROLES.includes(role);
}

export default {
  authorizeRoles,
  farmOwnerOnly,
  farmOwnerOrFarmer,
  buyerOrFarmOwner,
  anyRole,
  ROLES,
  ALL_ROLES,
};
