import jwt from 'jsonwebtoken';
import { config } from '../config/index.js';
import { AppError, ErrorCode } from './errors.js';

/**
 * Access tokens are short-lived JWTs carrying the minimum needed for
 * authorization: subject (userId) and role. Role is re-verified against the
 * database on every request (see middleware/auth.js) so a suspended or
 * role-changed user cannot keep using an old token.
 */
export function signAccessToken({ userId, role }) {
  return jwt.sign({ sub: userId, role, typ: 'access' }, config.jwt.accessSecret, {
    expiresIn: config.jwt.accessTtl,
    issuer: 'agriconnect',
    audience: 'agriconnect-mobile',
  });
}

export function verifyAccessToken(token) {
  try {
    return jwt.verify(token, config.jwt.accessSecret, {
      issuer: 'agriconnect',
      audience: 'agriconnect-mobile',
    });
  } catch (err) {
    if (err.name === 'TokenExpiredError') {
      throw new AppError(ErrorCode.TOKEN_EXPIRED, 'Your session has expired. Please sign in again.');
    }
    throw new AppError(ErrorCode.UNAUTHENTICATED, 'Your session is not valid. Please sign in again.');
  }
}

export function accessTokenTtlSeconds() {
  // jsonwebtoken accepts "15m"; decode to seconds for the client contract.
  const m = /^(\d+)([smhd])$/.exec(config.jwt.accessTtl);
  if (!m) return 900;
  const n = Number(m[1]);
  const mult = { s: 1, m: 60, h: 3600, d: 86400 }[m[2]];
  return n * mult;
}

export default { signAccessToken, verifyAccessToken, accessTokenTtlSeconds };
