import { describe, it, expect } from '@jest/globals';
import path from 'node:path';

import {
  IMAGE_MIME_EXT,
  extensionFor,
  isImageMime,
  publicUrl,
  storageDriver,
  storedNameFor,
  uploadsDir,
  STORED_NAME_PATTERN,
} from '../../src/services/uploads.js';

/**
 * Unit tests for the storage-independent half of the upload pipeline.
 *
 * These deliberately assert the *contract* rather than the mechanism: whatever
 * backend is configured, the value persisted in `users.avatar_url` /
 * `produce_listings.image_url` / `farm_plots.image_url` must stay an
 * origin-relative `/uploads/<name>` path. That invariant is what lets the backend
 * move disk → Supabase Storage (and back) with no data migration and no Android
 * change — `ImageUrls.resolve()` in the app depends on it.
 */

describe('image type allowlist', () => {
  it('accepts exactly jpeg, png and webp', () => {
    expect(isImageMime('image/jpeg')).toBe(true);
    expect(isImageMime('image/png')).toBe(true);
    expect(isImageMime('image/webp')).toBe(true);
  });

  it('rejects everything else, including near-misses', () => {
    expect(isImageMime('image/gif')).toBe(false);
    expect(isImageMime('image/svg+xml')).toBe(false);
    expect(isImageMime('image/heic')).toBe(false);
    expect(isImageMime('application/pdf')).toBe(false);
    expect(isImageMime('')).toBe(false);
    expect(isImageMime(undefined)).toBe(false);
  });

  it('maps each accepted type to a matching extension', () => {
    expect(extensionFor('image/jpeg')).toBe('.jpg');
    expect(extensionFor('image/png')).toBe('.png');
    expect(extensionFor('image/webp')).toBe('.webp');
  });

  it('returns no extension for a rejected type, so nothing can be smuggled through', () => {
    expect(extensionFor('image/gif')).toBe('');
    expect(extensionFor('text/html')).toBe('');
  });

  it('exposes the allowlist as a frozen map', () => {
    expect(Object.isFrozen(IMAGE_MIME_EXT)).toBe(true);
  });
});

describe('stored URL contract', () => {
  it('persists an origin-relative /uploads path, never an absolute URL', () => {
    // The filename is all multer/diskStorage puts on req.file; the route layer
    // never sees it. Keeping this relative is what makes storage swappable.
    expect(publicUrl({ filename: 'avatar-9f8e7d6c-1234-4abc-9def-000000000000.jpg' })).toBe(
      '/uploads/avatar-9f8e7d6c-1234-4abc-9def-000000000000.jpg',
    );
    expect(publicUrl({ filename: 'image-abc.png' })).toBe('/uploads/image-abc.png');
  });

  it('does not throw when the filename is absent (defensive, route always sets it)', () => {
    expect(publicUrl({})).toBe('/uploads/undefined');
  });
});

describe('driver selection', () => {
  it('defaults to local disk so a bare checkout needs no configuration', () => {
    expect(storageDriver).toBe('local');
  });

  it('resolves the uploads directory to an absolute path', () => {
    expect(path.isAbsolute(uploadsDir)).toBe(true);
  });
});

describe('stored names are servable (producer ↔ validator invariant)', () => {
  // Regression for a bug that shipped to production: `GET /uploads/:name` only serves
  // names matching [STORED_NAME_PATTERN], and the uploader builds names with
  // [storedNameFor]. The avatar route passed `'avatar-'` as the prefix, so every photo
  // was written as `avatar--<uuid>.png`, failed the pattern, and 404'd — profile images
  // never displayed. The old route tests missed it because they hand-wrote
  // `avatar-<uuid>.jpg` instead of running a name through the uploader's own logic.
  it('every name the uploader generates is accepted by the serving route', () => {
    for (const prefix of ['image', 'avatar']) {
      for (const mime of ['image/jpeg', 'image/png', 'image/webp']) {
        expect(STORED_NAME_PATTERN.test(storedNameFor(prefix, mime))).toBe(true);
      }
    }
  });

  it('produces exactly <prefix>-<uuid><ext>, with a single separator', () => {
    expect(storedNameFor('avatar', 'image/png')).toMatch(
      /^avatar-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.png$/,
    );
    expect(storedNameFor('image', 'image/jpeg')).toMatch(
      /^image-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jpg$/,
    );
  });

  it('still accepts the historical double-hyphen avatar names', () => {
    // Photos uploaded before the prefix fix are stored this way; they must keep
    // resolving so existing users do not silently lose their picture.
    expect(STORED_NAME_PATTERN.test('avatar--b9324b0a-a656-4f4d-b689-02d0be102065.png')).toBe(true);
  });

  it('rejects anything a client could hand-write to address another file', () => {
    for (const bad of [
      '../etc/passwd',
      'image-../../secret.png',
      'avatar-..%2f..%2fetc%2fpasswd',
      'image-12345.jpg',
      'not-a-uuid.jpg',
      'image-9f8e7d6c-1234-4abc-9def-000000000000.php',
      'image-9f8e7d6c-1234-4abc-9def-000000000000',
      'image-9f8e7d6c-1234-4abc-9def-000000000000.gif',
    ]) {
      expect(STORED_NAME_PATTERN.test(bad)).toBe(false);
    }
  });
});
