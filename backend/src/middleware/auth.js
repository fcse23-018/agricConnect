import { one } from '../db/index.js';
import { verifyAccessToken } from '../utils/jwt.js';
import { AppError, ErrorCode, unauthorized } from '../utils/errors.js';

/**
 * Verifies the bearer token AND re-loads the user from the database on every
 * request. This is what makes role changes, suspensions and deletion take effect
 * immediately rather than at token expiry — and it means a locally hidden button
 * is never the authorization boundary.
 */
export async function authenticate(req, _res, next) {
  try {
    const header = req.headers.authorization || '';
    const [scheme, token] = header.split(' ');
    if (!token || scheme.toLowerCase() !== 'bearer') throw unauthorized();

    const payload = verifyAccessToken(token);
    if (payload.typ !== 'access') throw unauthorized();

    const user = await one(
      `SELECT id, role, account_status, deleted_at
         FROM users WHERE id = $1`,
      [payload.sub],
    );
    if (!user) throw unauthorized('Your account is no longer available. Please sign in again.');
    if (user.account_status === 'SUSPENDED') {
      throw new AppError(ErrorCode.ACCOUNT_SUSPENDED, 'Your account has been suspended. Please contact support.');
    }
    if (user.deleted_at || user.account_status === 'DELETED') {
      throw unauthorized('Your account is no longer available. Please sign in again.');
    }

    // Role from the database wins over the role embedded in the token.
    req.user = { id: user.id, role: user.role, accountStatus: user.account_status };
    next();
  } catch (err) {
    next(err);
  }
}

/** Attach the user if a valid token is present, but never fail. For public routes. */
export async function optionalAuthenticate(req, _res, next) {
  const header = req.headers.authorization || '';
  if (!header.toLowerCase().startsWith('bearer ')) return next();
  try {
    await authenticate(req, _res, () => {});
  } catch {
    /* ignore — optional */
  }
  next();
}

export async function requireVerified(req, _res, next) {
  if (req.user?.accountStatus && ['UNVERIFIED', 'REGISTERED'].includes(req.user.accountStatus)) {
    return next(new AppError(ErrorCode.ACCOUNT_UNVERIFIED, 'Please verify your account to continue.'));
  }
  next();
}

export default { authenticate, optionalAuthenticate, requireVerified };
