import { describe, it, expect } from '@jest/globals';
import path from 'node:path';

import {
  IMAGE_MIME_EXT,
  extensionFor,
  isImageMime,
  publicUrl,
  storageDriver,
  uploadsDir,
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
