package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /app/version` (backend `src/modules/app/app.routes.js`).
 *
 * The endpoint always answers `200` with a JSON object, so the only way a parse
 * can fail is a genuinely malformed body. Every field therefore carries a safe
 * default and value-typed fields stay nullable: an older or partial server can
 * never crash the client, and a missing field degrades to "no update offered"
 * (see `bw.agriconnect.data.update.decide`).
 *
 * Three response shapes exist:
 *  - `available: false` + `reason` — updates disabled, no release published, or
 *    the manifest is unusable. Version fields may accompany it or be null.
 *  - `available: true` + the full published manifest.
 *  - any body an old server might emit — unknown keys are ignored
 *    (`ignoreUnknownKeys`), missing keys hit the defaults below.
 */
@Serializable
data class VersionManifestDto(
    /** `false` => never prompt, whatever else the body carries. */
    val available: Boolean = false,
    /** Present with `available = false`; explains why no update is offered. */
    val reason: String? = null,
    /** `false` while the manifest file has not been published. */
    val published: Boolean = false,
    val latestVersionName: String? = null,
    /** `0` when the server could not report one; never prompt on that. */
    val latestVersionCode: Int = 0,
    /** Oldest still-supported build; null on non-available responses. */
    val minSupportedVersionCode: Int? = null,
    /** Direct APK URL. The server enforces HTTPS; the client re-checks it. */
    val downloadUrl: String? = null,
    /** Release package (`bw.agriconnect`). Verified against the *installed*
     *  applicationId at install time, not here — debug builds differ. */
    val packageName: String? = null,
    /** Plain-text notes, one entry per bullet; may be empty. */
    val releaseNotes: List<String> = emptyList(),
    /** `YYYY-MM-DD` or null. */
    val releasedAt: String? = null,
    /** Copy for a REQUIRED prompt; the client falls back to its own text. */
    val requiredMessage: String? = null,
    /** Copy for an OPTIONAL prompt; the client falls back to its own text. */
    val optionalMessage: String? = null,
    /** Lower-case hex SHA-256 of the APK, or null when not published. */
    val checksumSha256: String? = null,
    /** Expected APK size in bytes, or null; used for the storage pre-check. */
    val sizeBytes: Long? = null,
    val releaseNotesUrl: String? = null,
)
