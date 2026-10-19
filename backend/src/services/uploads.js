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
 *    reaches storage.
 *
 * Two interchangeable backends, chosen by `UPLOAD_DRIVER`:
 *
 *  - `local` — writes to disk and serves from `/uploads`. Correct on a host with a
 *    persistent filesystem (your own PC, a VM, docker-compose).
 *
 *  - `supabase` — writes to Supabase Storage and has `/uploads/<name>` redirect to
 *    the object's public URL. Required on a free PaaS: Render's free tier has an
 *    *ephemeral* filesystem, so a file written to disk is destroyed on every
 *    redeploy **and every spindown** — an avatar would silently vanish.
 *
 * **The stored value is `/uploads/<uuid>.<ext>` under both drivers.** That is the
 * whole point: the database keeps an origin-relative path, so storage can move
 * between the two without a data migration, and the Android client
 * (`ImageUrls.resolve`) needs no change. Image bytes reach the client from
 * Supabase directly via the redirect, so they never consume the API host's
 * bandwidth — which matters on a free tier's monthly allowance.
 */

/** Accepted image MIME types and the extension each is stored with. */
export const IMAGE_MIME_EXT = Object.freeze({
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
});

const IMAGE_MIME_TYPES = new Set(Object.keys(IMAGE_MIME_EXT));

/** Active backend: `local` or `supabase`. */
export const storageDriver = config.uploads.driver === 'supabase' ? 'supabase' : 'local';

/** Absolute path of the uploads directory (created on demand). Local driver only. */
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
 * The shape of every filename this service writes.
 *
 * This is a security control as much as a format: `GET /uploads/:name` only
 * redirects names matching it, so a client cannot address a file we did not create.
 *
 * It lives here, next to [storedNameFor], and not inline in the route. The two once
 * drifted apart: the avatar route passed the prefix as `'avatar-'`, [storedNameFor]
 * adds its own separator, and every profile photo was therefore written as
 * `avatar--<uuid>.png` — rejected by the (separately written) route pattern and served
 * as a 404. Producer and validator being one source of truth is the fix.
 *
 * `-{1,2}` keeps those historical double-hyphen avatars resolving, so photos uploaded
 * before the fix do not have to be re-taken.
 */
export const STORED_NAME_PATTERN =
  /^(?:image|avatar)-{1,2}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:jpg|png|webp)$/;

/**
 * The filename an upload is stored under. [prefix] must **not** include the trailing
 * separator — this function adds it, so every caller produces `image-<uuid>.<ext>` or
 * `avatar-<uuid>.<ext>` and nothing else.
 */
export function storedNameFor(prefix, mimeType) {
  return `${prefix}-${randomUUID()}${extensionFor(mimeType)}`;
}

/**
 * The public URL path a stored file is served from. Pure — the value that is
 * persisted in `users.avatar_url`, `produce_listings.image_url`, … so storage
 * can move (disk → object storage) without a data migration.
 */
export function publicUrl(file) {
  return `/uploads/${file.filename}`;
}

// --- Supabase Storage driver -----------------------------------------------

/** The absolute public URL of a stored object. Only meaningful under the supabase driver. */
export function publicObjectUrl(filename) {
  const base = config.uploads.supabaseUrl.replace(/\/+$/, '');
  return `${base}/storage/v1/object/public/${encodeURIComponent(config.uploads.supabaseBucket)}/${filename}`;
}

let bucketEnsured = false;

/**
 * Create the storage bucket on first use, if it is missing.
 *
 * Best-effort and memoised: an operator who already created the bucket, or who
 * has scoped the service-role key to only one bucket, gets a 4xx here that we
 * deliberately ignore — the upload itself is the real test. This only exists so
 * a fresh deployment works without a manual dashboard step.
 */
async function ensureBucket() {
  if (bucketEnsured) return;
  const base = config.uploads.supabaseUrl.replace(/\/+$/, '');

  if (!config.uploads.supabaseServiceRoleKey) {
    console.error(
      '[uploads] SUPABASE_SERVICE_ROLE_KEY is empty — the bucket cannot be created and every upload will fail.',
    );
    return;
  }

  try {
    const res = await fetch(`${base}/storage/v1/bucket`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.uploads.supabaseServiceRoleKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name: config.uploads.supabaseBucket, public: true }),
    });
    // 200 created, 409 already exists — both fine.
    if (res.ok || res.status === 409) {
      bucketEnsured = true;
      return;
    }
    // Anything else is swallowed by the upload below, which reports a generic 500 to
    // the client. Log the real status so an operator can tell a missing bucket from a
    // rejected key: a 401/403 here means SUPABASE_SERVICE_ROLE_KEY is not the
    // service_role secret, and no upload can ever succeed.
    const detail = await res.text().catch(() => '');
    console.error(
      `[uploads] Could not ensure bucket "${config.uploads.supabaseBucket}": ${res.status} ${detail.slice(0, 300)}`,
    );
  } catch (err) {
    console.error(`[uploads] Could not reach Supabase Storage to ensure the bucket: ${err?.message || err}`);
  }
}

/** Stream [buffer] to Supabase Storage as [filename]. */
async function putObject({ filename, buffer, mimeType }) {
  await ensureBucket();
  const base = config.uploads.supabaseUrl.replace(/\/+$/, '');
  const url = `${base}/storage/v1/object/${encodeURIComponent(config.uploads.supabaseBucket)}/${filename}`;

  let res;
  try {
    res = await fetch(url, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${config.uploads.supabaseServiceRoleKey}`,
        'Content-Type': mimeType,
        'x-upsert': 'true',
      },
      body: buffer,
    });
  } catch (err) {
    // Network-level: do not leak the endpoint or the error to the client.
    throw new AppError(ErrorCode.INTERNAL, 'The image could not be stored. Please try again.', { cause: err });
  }

  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    // eslint-disable-next-line no-console
    console.error(`[uploads] Supabase Storage rejected ${filename}: ${res.status} ${detail.slice(0, 300)}`);
    throw new AppError(ErrorCode.INTERNAL, 'The image could not be stored. Please try again.');
  }
}

// --- Multer wiring ----------------------------------------------------------

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
    filename: (_req, file, cb) => cb(null, storedNameFor(prefix, file.mimetype)),
  });
}

/**
 * Express middleware that parses `multipart/form-data` and places the first
 * image under [field] on `req.file`, or a safe [AppError] on `next`.
 *
 * Under the `local` driver multer writes straight to disk. Under the `supabase`
 * driver multer buffers in memory (bounded by the same size cap) and this
 * middleware forwards the buffer to Storage, filling in `req.file.filename`
 * itself so that [publicUrl] and both call sites stay driver-agnostic.
 *
 * The callback-style error translation matters: multer's own `LIMIT_FILE_SIZE`
 * (and our `UNSUPPORTED_MEDIA`) would otherwise reach the error handler as an
 * opaque 500 instead of the actionable 400 the client shows verbatim.
 */
export function singleImage(field, prefix = 'image') {
  const useSupabase = storageDriver === 'supabase';

  const uploader = multer({
    storage: useSupabase ? multer.memoryStorage() : imageStorage(prefix),
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
      if (err) {
        if (err instanceof multer.MulterError) {
          if (err.code === 'LIMIT_FILE_SIZE') {
            const mb = Math.floor(config.uploads.maxBytes / (1024 * 1024));
            return next(badRequest(`Image must be ${mb} MB or smaller.`));
          }
          return next(badRequest('The image could not be uploaded.'));
        }
        return next(err);
      }

      if (!useSupabase || !req.file) return next();

      const filename = storedNameFor(prefix, req.file.mimetype);
      putObject({ filename, buffer: req.file.buffer, mimeType: req.file.mimetype })
        .then(() => {
          req.file.filename = filename;
          next();
        })
        .catch(next);
    });
  };
}

export default {
  singleImage,
  publicUrl,
  publicObjectUrl,
  isImageMime,
  extensionFor,
  storedNameFor,
  STORED_NAME_PATTERN,
  uploadsDir,
  storageDriver,
  IMAGE_MIME_EXT,
};
