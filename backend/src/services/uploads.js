import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import multer from 'multer';

import { config } from '../config/index.js';
import { AppError, ErrorCode, badRequest } from '../utils/errors.js';

/**
 * Image uploads (avatars, produce photos, plot photos).
 *
 * One pipeline serves every image in the product so the limits are enforced in
 * exactly one place:
 *  - jpeg/png/webp only ([IMAGE_MIME_EXT] doubles as the extension allowlist);
 *  - a hard size cap from `MAX_UPLOAD_BYTES` (multer aborts mid-stream);
 *  - UUID filenames, so a stored file can never be guessed, overwritten or
 *    traversed into — nothing from the client (including its filename) ever
 *    reaches the filesystem.
 *
 * Stored files are served read-only from `/uploads` (see the static mount in
 * `src/app.js`); the database only ever stores the returned URL path.
 */

/** Accepted image MIME types and the extension each is stored with. */
export const IMAGE_MIME_EXT = Object.freeze({
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
});

const IMAGE_MIME_TYPES = new Set(Object.keys(IMAGE_MIME_EXT));

/** Absolute path of the uploads directory (created on demand). */
export const uploadsDir = path.resolve(config.uploads.dir);

// Best-effort at startup; also ensured per request so a missing directory (or a
// read-only mount that appears later) never prevents the app from booting.
try {
  fs.mkdirSync(uploadsDir, { recursive: true });
} catch {
  /* surfaced when an upload is attempted */
}

/** True when [mimeType] is an accepted image type. Pure — unit-testable. */
export function isImageMime(mimeType) {
  return IMAGE_MIME_TYPES.has(mimeType);
}

/** The stored extension for [mimeType], or `''` when it is not accepted. Pure. */
export function extensionFor(mimeType) {
  return IMAGE_MIME_EXT[mimeType] || '';
}

/**
 * The public URL path a stored file is served from. Pure — the value that is
 * persisted in `users.avatar_url`, `produce_listings.image_url`, … so storage
 * can move (disk → object storage) without a data migration.
 */
export function publicUrl(file) {
  return `/uploads/${file.filename}`;
}

/** A disk-storage multer instance that prefixes stored names with [prefix]. */
function imageStorage(prefix) {
  return multer.diskStorage({
    destination: (_req, _file, cb) => {
      try {
        fs.mkdirSync(uploadsDir, { recursive: true });
        cb(null, uploadsDir);
      } catch (err) {
        cb(err);
      }
    },
    filename: (_req, file, cb) => cb(null, `${prefix}-${randomUUID()}${extensionFor(file.mimetype)}`),
  });
}

/**
 * Express middleware that parses `multipart/form-data` and places the first
 * image under [field] on `req.file`, or a safe [AppError] on `next`.
 *
 * The callback-style error translation matters: multer's own `LIMIT_FILE_SIZE`
 * (and our `UNSUPPORTED_MEDIA`) would otherwise reach the error handler as an
 * opaque 500 instead of the actionable 400 the client shows verbatim.
 */
export function singleImage(field, prefix = 'image') {
  const uploader = multer({
    storage: imageStorage(prefix),
    limits: { fileSize: config.uploads.maxBytes },
    fileFilter: (_req, file, cb) => {
      if (!isImageMime(file.mimetype)) {
        return cb(new AppError(ErrorCode.UNSUPPORTED_MEDIA, 'Only JPEG, PNG or WebP images are allowed.'));
      }
      cb(null, true);
    },
  });

  return (req, res, next) => {
    uploader.single(field)(req, res, (err) => {
      if (!err) return next();
      if (err instanceof multer.MulterError) {
        if (err.code === 'LIMIT_FILE_SIZE') {
          const mb = Math.floor(config.uploads.maxBytes / (1024 * 1024));
          return next(badRequest(`Image must be ${mb} MB or smaller.`));
        }
        return next(badRequest('The image could not be uploaded.'));
      }
      return next(err);
    });
  };
}

export default { singleImage, publicUrl, isImageMime, extensionFor, uploadsDir, IMAGE_MIME_EXT };
