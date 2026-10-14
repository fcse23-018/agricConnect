import bcrypt from 'bcryptjs';

const ROUNDS = 12;

/**
 * Passwords are NEVER stored in plaintext. bcryptjs is used deliberately
 * (pure JS) so the backend installs without native build tooling on Windows.
 */
export async function hashPassword(plain) {
  return bcrypt.hash(plain, ROUNDS);
}

export async function verifyPassword(plain, hash) {
  if (!hash) return false;
  try {
    return await bcrypt.compare(plain, hash);
  } catch {
    return false;
  }
}

/**
 * Password policy (server-side, authoritative): at least 8 characters,
 * at least one letter and at least one number. Mirrored on the client.
 */
export const PASSWORD_MIN_LENGTH = 8;

export function validatePasswordStrength(password) {
  const problems = [];
  if (typeof password !== 'string' || password.length < PASSWORD_MIN_LENGTH) {
    problems.push(`Password must be at least ${PASSWORD_MIN_LENGTH} characters.`);
  }
  if (password && password.length > 200) problems.push('Password is too long.');
  if (password && !/[A-Za-z]/.test(password)) problems.push('Password must contain at least one letter.');
  if (password && !/[0-9]/.test(password)) problems.push('Password must contain at least one number.');
  if (password && /^\s|\s$/.test(password)) problems.push('Password must not start or end with a space.');
  return { ok: problems.length === 0, problems };
}

export default { hashPassword, verifyPassword, validatePasswordStrength, PASSWORD_MIN_LENGTH };
