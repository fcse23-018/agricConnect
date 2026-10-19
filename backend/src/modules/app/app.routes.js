/**
 * APK update manifest.
 *
 * Android clients poll `GET /api/v1/app/version` on start and periodically while
 * online. The manifest is a file in the repository (`version-manifest.json`) that
 * is served as-is, which keeps the release procedure obvious: **uploading an APK
 * does not update this file**, and a release is not finished until the version
 * code and download URL here are bumped and committed. See
 * `docs/13-ANDROID-RELEASES.md`.
 *
 * Two rules this endpoint must obey:
 *
 *  1. It is public and unauthenticated. A client that cannot check for updates
 *     must not be locked out, and the check must work before login.
 *  2. It exposes nothing but what an APK needs: version numbers, an HTTPS
 *     download URL and release notes. No credentials, no internal paths.
 */

import { Router } from 'express';
import { readFile } from 'node:fs/promises';

import { asyncHandler } from '../../utils/asyncHandler.js';
import { config } from '../../config/index.js';

const router = Router();

/** Read the manifest from disk on each request so a deploy needs no restart. */
async function readManifest() {
  const raw = await readFile(config.updates.manifestPath, 'utf8');
  return JSON.parse(raw);
}
/**
 * Validate and normalise the manifest before it leaves the server.
 *
 * Two distinct failure modes, deliberately not collapsed:
 *
 *  - **No release published yet** (`published: false` or no `downloadUrl`). This
 *    is a normal state on a fresh checkout, so it returns a "not published"
 *    marker rather than an error, and clients show no update prompt.
 *  - **Malformed** (a version code that is not a number, a non-HTTPS URL). This
 *    throws, because a client that parses a half-formed manifest could conclude
 *    "no update" from a release that really exists.
 */
export function normaliseManifest(raw) {
  const hasDownload = Boolean(raw?.downloadUrl);
  if (raw?.published === false || !hasDownload) {
    return {
      published: false,
      latestVersionName: raw?.latestVersionName ? String(raw.latestVersionName) : null,
      latestVersionCode: Number.isFinite(Number.parseInt(raw?.latestVersionCode, 10))
        ? Number.parseInt(raw.latestVersionCode, 10)
        : null,
      minSupportedVersionCode: null,
      downloadUrl: null,
      packageName: raw?.packageName ?? config.updates.packageName,
      releaseNotes: [],
      releasedAt: null,
      requiredMessage: raw?.requiredMessage ?? 'Please update AgriConnect to continue.',
      optionalMessage: raw?.optionalMessage ?? 'A newer version of AgriConnect is available.',
      checksumSha256: null,
      sizeBytes: null,
      releaseNotesUrl: config.updates.releaseNotesUrl || null,
    };
  }

  const latestVersionCode = Number.parseInt(raw?.latestVersionCode, 10);
  const minSupportedVersionCode = Number.parseInt(raw?.minSupportedVersionCode ?? 1, 10);

  if (!Number.isFinite(latestVersionCode) || latestVersionCode <= 0) {
    throw new Error('version manifest has no usable latestVersionCode');
  }
  if (!raw?.latestVersionName) {
    throw new Error('version manifest has no latestVersionName');
  }
  // HTTPS only. An APK fetched over plain HTTP could be modified in transit, and
  // Android would still install it if the signature happened to match.
  if (!/^https:\/\//i.test(String(raw.downloadUrl))) {
    throw new Error('version manifest downloadUrl must be HTTPS');
  }

  return {
    published: true,
    latestVersionName: String(raw.latestVersionName),
    latestVersionCode,
    minSupportedVersionCode: Math.max(1, Math.min(latestVersionCode, minSupportedVersionCode)),
    downloadUrl: String(raw.downloadUrl),
    packageName: raw.packageName ?? config.updates.packageName,
    releaseNotes: Array.isArray(raw.releaseNotes) ? raw.releaseNotes.map(String) : [],
    releasedAt: raw.releasedAt ?? null,
    requiredMessage: raw.requiredMessage ?? 'Please update AgriConnect to continue.',
    optionalMessage: raw.optionalMessage ?? 'A newer version of AgriConnect is available.',
    checksumSha256: raw.checksumSha256 ?? null,
    sizeBytes: Number.isFinite(Number(raw.sizeBytes)) ? Number(raw.sizeBytes) : null,
    releaseNotesUrl: config.updates.releaseNotesUrl || null,
  };
}

/**
 * GET /app/version
 *
 * Never fails the client: if the manifest is missing or malformed the response is
 * `available: false` with a reason, and the app simply continues without an
 * update prompt. A required update is only ever decided by the *client*
 * comparing its own version code against the numbers below — the server never
 * tells a device it is not allowed to run.
 */
router.get(
  '/version',
  asyncHandler(async (_req, res) => {
    if (!config.updates.enabled) {
      return res.json({ available: false, reason: 'Update checking is disabled on this server.' });
    }

    try {
      const manifest = normaliseManifest(await readManifest());
      // Deliberately a short cache: a release should reach devices promptly, but
      // a fleet waking at once should not hammer the filesystem.
      res.set('Cache-Control', 'public, max-age=300');
      if (!manifest.published) {
        return res.json({
          available: false,
          reason: 'No new release is published for this app yet.',
          latestVersionName: manifest.latestVersionName,
          latestVersionCode: manifest.latestVersionCode,
          packageName: manifest.packageName,
        });
      }
      return res.json({ available: true, ...manifest });
    } catch (err) {
      if (!config.isTest) {
        // eslint-disable-next-line no-console
        console.warn('[app] version manifest is unusable', { error: err?.message });
      }
      return res.json({
        available: false,
        reason: 'No usable version manifest is published.',
      });
    }
  }),
);

export default router;
