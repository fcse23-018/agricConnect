import { describe, it, expect } from '@jest/globals';

import {
  sha256,
  randomToken,
  randomNumericCode,
  safeEqual,
  maskIdentifier,
  pseudonymFor,
} from '../../src/utils/crypto.js';

describe('sha256 — used to store tokens at rest', () => {
  it('is deterministic', () => {
    expect(sha256('abc')).toBe(sha256('abc'));
  });

  it('matches the known SHA-256 vector for "abc"', () => {
    expect(sha256('abc')).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
  });

  it('is not reversible by inspection (token is not recoverable)', () => {
    const token = 'super-secret-token';
    expect(sha256(token)).not.toContain(token);
  });
});

describe('randomToken', () => {
  it('produces distinct, non-trivial tokens', () => {
    const a = randomToken();
    const b = randomToken();
    expect(a).not.toBe(b);
    expect(a.length).toBeGreaterThanOrEqual(40); // 32 bytes base64url
  });

  it('is URL-safe', () => {
    expect(randomToken()).toMatch(/^[A-Za-z0-9_-]+$/);
  });
});

describe('randomNumericCode', () => {
  it('is the requested fixed width, zero-padded', () => {
    for (let i = 0; i < 50; i += 1) {
      const code = randomNumericCode(6);
      expect(code).toMatch(/^[0-9]{6}$/);
    }
  });

  it('is unbiased-ish: produces more than one distinct value over many draws', () => {
    const seen = new Set();
    for (let i = 0; i < 200; i += 1) seen.add(randomNumericCode(6));
    expect(seen.size).toBeGreaterThan(50);
  });
});

describe('safeEqual — constant-time comparison', () => {
  it('is true for identical strings', () => {
    expect(safeEqual('token-123', 'token-123')).toBe(true);
  });

  it('is false for different strings, including different lengths', () => {
    expect(safeEqual('token-123', 'token-124')).toBe(false);
    expect(safeEqual('token-123', 'token-1234')).toBe(false);
  });
});

describe('maskIdentifier — never echoes a full phone/email in messages', () => {
  it('masks the local part of an email', () => {
    expect(maskIdentifier('thabo@example.bw')).toBe('t****@example.bw');
  });

  it('masks the middle of a phone number', () => {
    const masked = maskIdentifier('+26771234567');
    expect(masked.startsWith('+267')).toBe(true);
    expect(masked.endsWith('567')).toBe(true);
    expect(masked).toContain('*');
    expect(masked).not.toBe('+26771234567');
  });

  it('fully masks very short values', () => {
    expect(maskIdentifier('1234')).toBe('****');
  });
});

describe('pseudonymFor — links retained records without re-identifying', () => {
  it('is stable for the same user id and secret', () => {
    expect(pseudonymFor('user-1', 's')).toBe(pseudonymFor('user-1', 's'));
  });

  it('differs across users', () => {
    expect(pseudonymFor('user-1', 's')).not.toBe(pseudonymFor('user-2', 's'));
  });

  it('does not contain the raw user id', () => {
    expect(pseudonymFor('user-1234', 's')).not.toContain('user-1234');
  });
});
