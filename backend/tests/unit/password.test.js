import { describe, it, expect } from '@jest/globals';

import {
  hashPassword,
  verifyPassword,
  validatePasswordStrength,
  PASSWORD_MIN_LENGTH,
} from '../../src/utils/password.js';

describe('password hashing — passwords are never stored in plaintext', () => {
  it('produces a bcrypt hash, not the password itself', async () => {
    const hash = await hashPassword('correct horse 7');
    expect(hash).not.toBe('correct horse 7');
    expect(hash).toMatch(/^\$2[aby]\$/); // bcrypt identifier
  });

  it('never returns a hash that contains the plaintext', async () => {
    const hash = await hashPassword('mysecret123');
    expect(hash.includes('mysecret123')).toBe(false);
  });

  it('salts: the same password hashes differently every time', async () => {
    const a = await hashPassword('mysecret123');
    const b = await hashPassword('mysecret123');
    expect(a).not.toBe(b);
  });

  it('verifies the correct password and rejects a wrong one', async () => {
    const hash = await hashPassword('mysecret123');
    await expect(verifyPassword('mysecret123', hash)).resolves.toBe(true);
    await expect(verifyPassword('mysecret124', hash)).resolves.toBe(false);
  });

  it('fails closed for a missing/corrupt hash rather than throwing', async () => {
    await expect(verifyPassword('x', null)).resolves.toBe(false);
    await expect(verifyPassword('x', 'not-a-hash')).resolves.toBe(false);
  });
});

describe('password policy', () => {
  it('documents a minimum length of 8', () => {
    expect(PASSWORD_MIN_LENGTH).toBe(8);
  });

  it('accepts a password with letters and numbers of sufficient length', () => {
    expect(validatePasswordStrength('farmer2026').ok).toBe(true);
  });

  it('rejects short passwords', () => {
    const r = validatePasswordStrength('a1b2');
    expect(r.ok).toBe(false);
    expect(r.problems.join(' ')).toMatch(/8 characters/);
  });

  it('rejects a password with no number', () => {
    expect(validatePasswordStrength('onlyletters').ok).toBe(false);
  });

  it('rejects a password with no letter', () => {
    expect(validatePasswordStrength('12345678').ok).toBe(false);
  });

  it('rejects leading/trailing whitespace', () => {
    expect(validatePasswordStrength(' farmer2026').ok).toBe(false);
    expect(validatePasswordStrength('farmer2026 ').ok).toBe(false);
  });

  it('rejects non-string input without throwing', () => {
    expect(validatePasswordStrength(null).ok).toBe(false);
    expect(validatePasswordStrength(undefined).ok).toBe(false);
  });
});
