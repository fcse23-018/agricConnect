import crypto from 'node:crypto';

/** SHA-256 hex digest — used to store refresh/verification tokens at rest. */
export function sha256(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex');
}

/** URL-safe random token (default 32 bytes → 43 chars). */
export function randomToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url');
}

/** Numeric OTP, cryptographically random, fixed width. */
export function randomNumericCode(digits = 6) {
  const max = 10 ** digits;
  const n = crypto.randomInt(0, max);
  return String(n).padStart(digits, '0');
}

/** Constant-time string comparison to avoid timing leaks. */
export function safeEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

/** Mask an email or phone for display: j***@x.com, +267*****123 */
export function maskIdentifier(identifier = '') {
  const s = String(identifier);
  if (s.includes('@')) {
    const [name, domain] = s.split('@');
    const head = name.slice(0, 1);
    return `${head}${'*'.repeat(Math.max(1, name.length - 1))}@${domain}`;
  }
  if (s.length <= 4) return '*'.repeat(s.length);
  return `${s.slice(0, 4)}${'*'.repeat(Math.max(1, s.length - 7))}${s.slice(-3)}`;
}

/** Non-reversible pseudonym used to link retained records after anonymisation. */
export function pseudonymFor(userId, secret = 'agriconnect') {
  return `psn_${sha256(`${userId}:${secret}`).slice(0, 32)}`;
}

export default { sha256, randomToken, randomNumericCode, safeEqual, maskIdentifier, pseudonymFor };
