import { config } from '../../config/index.js';
import { one, many, query, withTransaction } from '../../db/index.js';
import { hashPassword, verifyPassword, validatePasswordStrength } from '../../utils/password.js';
import { signAccessToken, accessTokenTtlSeconds } from '../../utils/jwt.js';
import {
  sha256,
  randomToken,
  randomNumericCode,
  safeEqual,
  maskIdentifier,
} from '../../utils/crypto.js';
import {
  AppError,
  ErrorCode,
  badRequest,
  unauthorized,
  invalidCredentials,
  duplicateAccount,
} from '../../utils/errors.js';
import { toPublicUser } from '../../utils/serialize.js';
import { sendVerificationCode, sendPasswordResetCode } from '../../services/delivery.js';
import { audit } from '../../services/audit.js';

/**
 * Authentication domain service. Routes stay thin; every rule about tokens,
 * lockouts and code lifecycles is enforced here so it applies identically to
 * the mobile client, the public deletion page and any future caller.
 */

/**
 * Public user plus the inferred employer link (migration 005 gap). The field is
 * only meaningful for FARMER accounts (someone who works another's land), so it is
 * added for them and left off everyone else to keep the contract's user shape intact.
 */
export function serializeUser(row) {
  if (!row) return null;
  const user = toPublicUser(row);
  if (row.role === 'FARMER') {
    user.employerFarmerId = row.employer_farmer_id ?? null;
  }
  return user;
}

/**
 * Resolve a login/verification identifier to a live user. Email is matched
 * case-insensitively (citext); phone numbers ignore embedded whitespace.
 */
export async function findUserByIdentifier(identifier) {
  const raw = String(identifier ?? '').trim();
  if (!raw) return null;
  if (raw.includes('@')) {
    return one(`SELECT * FROM users WHERE lower(email) = lower($1) AND deleted_at IS NULL`, [raw]);
  }
  return one(`SELECT * FROM users WHERE phone_number = $1 AND deleted_at IS NULL`, [raw.replace(/\s+/g, '')]);
}

export async function findUserById(userId) {
  return one(`SELECT * FROM users WHERE id = $1`, [userId]);
}

/**
 * A bcrypt comparison is deliberately performed even when no account exists so
 * that response timing does not reveal whether an identifier is registered.
 * Generated lazily once and reused; it never matches a real password.
 */
let dummyHashPromise = null;
function dummyPasswordHash() {
  if (!dummyHashPromise) dummyHashPromise = hashPassword(randomToken(12));
  return dummyHashPromise;
}

/**
 * Invalidate any outstanding codes for a purpose, then mint a fresh 6-digit
 * code stored only as a hash. Returns the plaintext code so the caller can
 * deliver it (and, in development only, surface it).
 *
 * The hash is scoped with the user id because six-digit codes collide across
 * users easily, and `verification_tokens.token_hash` is UNIQUE. Scoping keeps
 * the stored value per-(user, code) without changing lookup semantics.
 */
export async function createVerificationCode(userId, purpose, { ttlMinutes, destination } = {}) {
  await query(
    `UPDATE verification_tokens SET consumed_at = now()
      WHERE user_id = $1 AND purpose = $2 AND consumed_at IS NULL`,
    [userId, purpose],
  );
  const expiresAt = new Date(Date.now() + ttlMinutes * 60_000);

  // `token_hash` is UNIQUE across all rows (consumed included), so a freshly
  // generated code can, rarely, collide with an earlier one for the same user
  // and purpose. Retry with a new code rather than failing the request.
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const code = randomNumericCode(6);
    try {
      await query(
        `INSERT INTO verification_tokens (user_id, purpose, token_hash, destination, expires_at)
         VALUES ($1,$2,$3,$4,$5)`,
        [userId, purpose, sha256(`${userId}:${purpose}:${code}`), destination ?? null, expiresAt],
      );
      return code;
    } catch (err) {
      if (err && err.code === '23505') continue;
      throw err;
    }
  }
  throw new AppError(ErrorCode.INTERNAL, 'Could not generate a verification code. Please try again.');
}

/**
 * Match a submitted numeric code against the user's outstanding tokens for the
 * given purposes. Wrong codes increment `attempts` (capped by config) and the
 * failure message never distinguishes "no account" from "bad code".
 */
export async function matchNumericCode(userId, purposes, code) {
  const tokens = await many(
    `SELECT id, purpose, token_hash, attempts, expires_at
       FROM verification_tokens
      WHERE user_id = $1 AND purpose::text = ANY($2::text[]) AND consumed_at IS NULL
      ORDER BY created_at DESC`,
    [userId, purposes],
  );

  const matched = tokens.find(
    (token) => safeEqual(sha256(`${userId}:${token.purpose}:${code}`), token.token_hash),
  );
  if (!matched) {
    // Only the most recent token is penalised so a leaked old code cannot be
    // used to exhaust the attempts budget of a fresh one.
    if (tokens[0]) {
      await query(`UPDATE verification_tokens SET attempts = attempts + 1 WHERE id = $1`, [tokens[0].id]);
    }
    throw badRequest('That code is invalid or has expired.');
  }
  if (new Date(matched.expires_at).getTime() < Date.now()) {
    throw badRequest('That code has expired. Please request a new one.');
  }
  if (matched.attempts >= config.tokens.maxVerificationAttempts) {
    throw badRequest('Too many incorrect attempts. Please request a new code.');
  }
  return matched;
}

/**
 * Issue a short-lived JWT access token plus an opaque, rotating refresh token.
 * Refresh tokens are stored only as a SHA-256 hash; the raw value is returned
 * to the client once and is never logged.
 */
export async function issueTokens(user, { userAgent } = {}) {
  const accessToken = signAccessToken({ userId: user.id, role: user.role });
  const refreshToken = randomToken(48);
  const expiresAt = new Date(Date.now() + config.jwt.refreshTtlDays * 86_400_000);
  await query(
    `INSERT INTO refresh_tokens (user_id, token_hash, user_agent, expires_at)
     VALUES ($1,$2,$3,$4)`,
    [user.id, sha256(refreshToken), userAgent ?? null, expiresAt],
  );
  return { accessToken, refreshToken, expiresIn: accessTokenTtlSeconds() };
}

export async function revokeAllRefreshTokens(userId) {
  await query(`UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, [userId]);
}

export async function revokeRefreshToken(userId, refreshToken) {
  if (!refreshToken) {
    await revokeAllRefreshTokens(userId);
    return;
  }
  await query(
    `UPDATE refresh_tokens SET revoked_at = now()
      WHERE user_id = $1 AND token_hash = $2 AND revoked_at IS NULL`,
    [userId, sha256(refreshToken)],
  );
}

export async function registerUser({ fullName, phoneNumber, email, password, role }, req) {
  // Friendly pre-checks; the partial unique indexes below are the race-proof guard.
  const phoneTaken = await one(`SELECT id FROM users WHERE phone_number = $1 AND deleted_at IS NULL`, [phoneNumber]);
  if (phoneTaken) throw duplicateAccount();

  if (email) {
    const emailTaken = await one(
      `SELECT id FROM users WHERE lower(email) = lower($1) AND deleted_at IS NULL`,
      [email],
    );
    if (emailTaken) throw duplicateAccount();
  }

  const passwordHash = await hashPassword(password);
  let user;
  try {
    user = await one(
      `INSERT INTO users (full_name, phone_number, email, password_hash, role, account_status)
       VALUES ($1,$2,$3,$4,$5,'UNVERIFIED')
       RETURNING *`,
      [fullName, phoneNumber, email ?? null, passwordHash, role],
    );
  } catch (err) {
    // 23505 unique_violation — a concurrent registration won the race.
    if (err && err.code === '23505') throw duplicateAccount();
    throw err;
  }

  const ttlMinutes = config.tokens.verificationCodeTtlMinutes;
  const code = await createVerificationCode(user.id, 'PHONE_VERIFICATION', {
    ttlMinutes,
    destination: maskIdentifier(phoneNumber),
  });
  await sendVerificationCode({ to: phoneNumber, code, expiresMinutes: ttlMinutes });
  if (email) {
    // The same code is valid for either channel; whichever the user opens works.
    await sendVerificationCode({ to: email, code, expiresMinutes: ttlMinutes });
  }

  const tokens = await issueTokens(user, { userAgent: req?.headers?.['user-agent'] });
  await audit(req, 'ACCOUNT_REGISTERED', { entityType: 'user', entityId: user.id, metadata: { role } });

  return {
    user,
    ...tokens,
    // Development-only: only present when EXPOSE_DEV_CODES is enabled (never in production).
    devVerificationCode: config.delivery.exposeDevCodes ? code : undefined,
  };
}

export async function loginUser({ identifier, password }, req) {
  const user = await findUserByIdentifier(identifier);
  if (!user) {
    await verifyPassword(password, await dummyPasswordHash());
    throw invalidCredentials();
  }

  if (user.locked_until && new Date(user.locked_until).getTime() > Date.now()) {
    throw new AppError(ErrorCode.RATE_LIMITED, 'Too many failed attempts. Please try again in a few minutes.');
  }
  if (user.account_status === 'SUSPENDED') {
    throw new AppError(ErrorCode.ACCOUNT_SUSPENDED, 'Your account has been suspended. Please contact support.');
  }
  if (user.deleted_at || user.account_status === 'DELETED') {
    throw invalidCredentials();
  }

  const ok = await verifyPassword(password, user.password_hash);
  if (!ok) {
    const nextCount = user.failed_login_count + 1;
    const shouldLock = nextCount >= 5;
    await query(
      `UPDATE users
          SET failed_login_count = $2,
              locked_until = CASE WHEN $3 THEN now() + interval '15 minutes' ELSE locked_until END
        WHERE id = $1`,
      [user.id, nextCount, shouldLock],
    );
    await audit(req, 'LOGIN_FAILED', { entityType: 'user', entityId: user.id });
    throw invalidCredentials();
  }

  await query(
    `UPDATE users
        SET failed_login_count = 0, locked_until = NULL, last_login_at = now()
      WHERE id = $1`,
    [user.id],
  );
  const tokens = await issueTokens(user, { userAgent: req?.headers?.['user-agent'] });
  await audit(req, 'LOGIN_SUCCESS', { entityType: 'user', entityId: user.id });
  return { user, ...tokens };
}

/**
 * Rotate a refresh token. Presenting an already-revoked token is treated as
 * reuse (theft): every session for that user is revoked and the caller gets 401.
 */
export async function rotateRefreshToken(refreshToken, req) {
  const tokenHash = sha256(refreshToken);
  const row = await one(
    `SELECT rt.id, rt.user_id, rt.expires_at, rt.revoked_at,
            u.role, u.account_status, u.deleted_at
       FROM refresh_tokens rt
       JOIN users u ON u.id = rt.user_id
      WHERE rt.token_hash = $1`,
    [tokenHash],
  );
  if (!row) throw unauthorized('Your session is no longer valid. Please sign in again.');

  if (row.revoked_at) {
    await revokeAllRefreshTokens(row.user_id);
    await audit(req, 'TOKEN_REUSE_DETECTED', { entityType: 'user', entityId: row.user_id });
    throw unauthorized('Your session is no longer valid. Please sign in again.');
  }
  if (new Date(row.expires_at).getTime() <= Date.now()) {
    throw unauthorized('Your session has expired. Please sign in again.');
  }
  if (row.deleted_at || row.account_status === 'DELETED') throw invalidCredentials();
  if (row.account_status === 'SUSPENDED') {
    throw new AppError(ErrorCode.ACCOUNT_SUSPENDED, 'Your account has been suspended. Please contact support.');
  }

  const expiresAt = new Date(Date.now() + config.jwt.refreshTtlDays * 86_400_000);
  const rawRefreshToken = randomToken(48);
  return withTransaction(async (client) => {
    const inserted = await client.query(
      `INSERT INTO refresh_tokens (user_id, token_hash, user_agent, expires_at)
       VALUES ($1,$2,$3,$4) RETURNING id`,
      [row.user_id, sha256(rawRefreshToken), req?.headers?.['user-agent'] ?? null, expiresAt],
    );
    const replacementId = inserted.rows[0].id;
    await client.query(
      `UPDATE refresh_tokens SET revoked_at = now(), replaced_by = $2 WHERE id = $1`,
      [row.id, replacementId],
    );
    return {
      accessToken: signAccessToken({ userId: row.user_id, role: row.role }),
      refreshToken: rawRefreshToken,
      expiresIn: accessTokenTtlSeconds(),
    };
  });
}

export async function requestPasswordReset(identifier, req) {
  const message = 'If an account matches those details, a recovery code has been sent.';
  const user = await findUserByIdentifier(identifier);
  let devCode;

  if (user && !user.deleted_at && user.account_status !== 'DELETED') {
    const ttlMinutes = config.tokens.passwordResetTtlMinutes;
    const destination = String(identifier).trim();
    const code = await createVerificationCode(user.id, 'PASSWORD_RESET', {
      ttlMinutes,
      destination: maskIdentifier(destination),
    });
    await sendPasswordResetCode({ to: destination, code, expiresMinutes: ttlMinutes });
    await audit(req, 'PASSWORD_RESET_REQUESTED', { entityType: 'user', entityId: user.id });
    if (config.delivery.exposeDevCodes) devCode = code;
  }

  return { message, devCode };
}

/**
 * Verify a recovery code and exchange it for a single-use reset token.
 *
 * Choice of the two documented options: we mark the submitted code consumed and
 * create a *second* PASSWORD_RESET token row holding the SHA-256 of a 32-byte
 * random `resetToken` with a fixed 15-minute TTL. A distinct random token (rather
 * than reusing the code) means the short numeric code is no longer sufficient
 * once the second stage is reached.
 */
export async function verifyPasswordResetCode(identifier, code, req) {
  const user = await findUserByIdentifier(identifier);
  if (!user || user.deleted_at || user.account_status === 'DELETED') {
    throw badRequest('That code is invalid or has expired.');
  }

  const matched = await matchNumericCode(user.id, ['PASSWORD_RESET'], code);
  const resetToken = randomToken(32);
  const expiresAt = new Date(Date.now() + config.tokens.passwordResetTtlMinutes * 60_000);

  await withTransaction(async (client) => {
    await client.query(`UPDATE verification_tokens SET consumed_at = now() WHERE id = $1`, [matched.id]);
    await client.query(
      `INSERT INTO verification_tokens (user_id, purpose, token_hash, destination, expires_at)
       VALUES ($1,'PASSWORD_RESET',$2,NULL,$3)`,
      [user.id, sha256(resetToken), expiresAt],
    );
  });

  await audit(req, 'PASSWORD_RESET_CODE_VERIFIED', { entityType: 'user', entityId: user.id });
  return { resetToken };
}

export async function resetPassword(resetToken, newPassword, req) {
  const policy = validatePasswordStrength(newPassword);
  if (!policy.ok) {
    throw badRequest(
      'Please choose a stronger password.',
      policy.problems.map((message) => ({ field: 'newPassword', message })),
    );
  }

  const token = await one(
    `SELECT id, user_id, expires_at FROM verification_tokens
      WHERE token_hash = $1 AND purpose = 'PASSWORD_RESET' AND consumed_at IS NULL`,
    [sha256(resetToken)],
  );
  if (!token) throw badRequest('That reset link is invalid or has expired.');
  if (new Date(token.expires_at).getTime() < Date.now()) {
    throw badRequest('That reset link has expired. Please start again.');
  }

  const passwordHash = await hashPassword(newPassword);
  await withTransaction(async (client) => {
    await client.query(
      `UPDATE users SET password_hash = $2, failed_login_count = 0, locked_until = NULL WHERE id = $1`,
      [token.user_id, passwordHash],
    );
    await client.query(`UPDATE verification_tokens SET consumed_at = now() WHERE id = $1`, [token.id]);
    await client.query(
      `UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`,
      [token.user_id],
    );
  });

  await audit(req, 'PASSWORD_RESET', { entityType: 'user', entityId: token.user_id });
  return { message: 'Your password has been reset. Please sign in with your new password.' };
}

export async function requestVerificationCode(identifier, req) {
  const message = 'If an account matches those details, a verification code has been sent.';
  const user = await findUserByIdentifier(identifier);
  let devCode;

  if (user && !user.deleted_at && user.account_status !== 'DELETED' && !user.verified_at) {
    const isEmail = String(identifier).includes('@');
    const ttlMinutes = config.tokens.verificationCodeTtlMinutes;
    const code = await createVerificationCode(user.id, isEmail ? 'EMAIL_VERIFICATION' : 'PHONE_VERIFICATION', {
      ttlMinutes,
      destination: maskIdentifier(identifier),
    });
    await sendVerificationCode({ to: String(identifier).trim(), code, expiresMinutes: ttlMinutes });
    await audit(req, 'VERIFICATION_CODE_REQUESTED', { entityType: 'user', entityId: user.id });
    if (config.delivery.exposeDevCodes) devCode = code;
  }

  return { message, devCode };
}

export async function confirmVerification(identifier, code, req) {
  const user = await findUserByIdentifier(identifier);
  if (!user || user.deleted_at || user.account_status === 'DELETED') {
    throw badRequest('That code is invalid or has expired.');
  }
  if (user.verified_at || user.account_status === 'VERIFIED') {
    return { message: 'Your account is already verified.' };
  }

  const matched = await matchNumericCode(user.id, ['PHONE_VERIFICATION', 'EMAIL_VERIFICATION'], code);
  await withTransaction(async (client) => {
    await client.query(`UPDATE verification_tokens SET consumed_at = now() WHERE id = $1`, [matched.id]);
    await client.query(
      `UPDATE users SET account_status = 'VERIFIED', verified_at = now() WHERE id = $1`,
      [user.id],
    );
  });

  await audit(req, 'ACCOUNT_VERIFIED', { entityType: 'user', entityId: user.id });
  return { message: 'Your account has been verified.' };
}

export default {
  serializeUser,
  findUserByIdentifier,
  findUserById,
  createVerificationCode,
  matchNumericCode,
  issueTokens,
  revokeAllRefreshTokens,
  revokeRefreshToken,
  registerUser,
  loginUser,
  rotateRefreshToken,
  requestPasswordReset,
  verifyPasswordResetCode,
  resetPassword,
  requestVerificationCode,
  confirmVerification,
};
