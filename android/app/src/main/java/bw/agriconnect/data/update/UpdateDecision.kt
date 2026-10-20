package bw.agriconnect.data.update

import bw.agriconnect.data.remote.dto.VersionManifestDto

/**
 * What a completed update check concluded.
 *
 * The server NEVER tells a device it is blocked (it only publishes version
 * numbers); the *client* decides by comparing its own version code against
 * `minSupportedVersionCode` / `latestVersionCode`. A failed or skipped check
 * never produces a prompt at all — see [UpdateChecker].
 */
sealed class UpdateDecision {
    /** Why this decision was reached — for logs and tests, never shown raw. */
    abstract val reason: String

    /** No prompt: no release published, unusable manifest, or already current. */
    data class None(override val reason: String) : UpdateDecision()

    /** A newer build exists; the user may update now or defer ("Later"). */
    data class Optional(
        val manifest: VersionManifestDto,
        override val reason: String,
    ) : UpdateDecision()

    /** The installed build is below `minSupportedVersionCode`: must update. */
    data class Required(
        val manifest: VersionManifestDto,
        override val reason: String,
    ) : UpdateDecision()
}

/** The manifest carried by [UpdateDecision.Optional]/[UpdateDecision.Required],
 *  or null for [UpdateDecision.None]. */
val UpdateDecision.manifestOrNull: VersionManifestDto?
    get() = when (this) {
        is UpdateDecision.Optional -> manifest
        is UpdateDecision.Required -> manifest
        is UpdateDecision.None -> null
    }

/**
 * Pure decision function: given the installed version code and the server
 * manifest, what (if anything) must be shown?
 *
 * Core rules:
 *  - `currentVersionCode < minSupportedVersionCode` => [UpdateDecision.Required]
 *  - `currentVersionCode < latestVersionCode`       => [UpdateDecision.Optional]
 *  - otherwise (equal or newer installed build)     => [UpdateDecision.None]
 *
 * Defensive rules, all unit-tested (`UpdateDecisionTest`):
 *  - `available: false` (whatever `reason` says) => None.
 *  - missing / non-positive `latestVersionCode`  => None (nothing to compare).
 *  - missing or non-HTTPS `downloadUrl`          => None — never prompt for an
 *    install that could not be performed securely (the server enforces HTTPS
 *    too, this is a second line of defence).
 *  - `minSupportedVersionCode` is clamped into `1..latest`, so a hand-edited
 *    manifest whose min is *above* its latest can never force an update on the
 *    newest build (which would be an unescapable prompt), and a min below 1
 *    behaves like the server default of 1.
 */
fun decide(currentVersionCode: Int, manifest: VersionManifestDto): UpdateDecision {
    if (!manifest.available) {
        return UpdateDecision.None(manifest.reason ?: "No update information available.")
    }
    val latest = manifest.latestVersionCode
    if (latest <= 0) {
        return UpdateDecision.None("The server reported no usable latest version code.")
    }
    val downloadUrl = manifest.downloadUrl
    if (downloadUrl.isNullOrBlank() || !downloadUrl.startsWith("https://", ignoreCase = true)) {
        return UpdateDecision.None("The release download URL is missing or not HTTPS.")
    }
    val minSupported = (manifest.minSupportedVersionCode ?: FALLBACK_MIN_SUPPORTED)
        .coerceIn(1, latest)

    return when {
        currentVersionCode < minSupported -> UpdateDecision.Required(
            manifest = manifest,
            reason = "installed=$currentVersionCode < minSupported=$minSupported (latest=$latest)",
        )

        currentVersionCode < latest -> UpdateDecision.Optional(
            manifest = manifest,
            reason = "installed=$currentVersionCode < latest=$latest (minSupported=$minSupported)",
        )

        else -> UpdateDecision.None(
            reason = "installed=$currentVersionCode is current (latest=$latest)",
        )
    }
}

/** The backend's own default when a published manifest omits the field. */
private const val FALLBACK_MIN_SUPPORTED = 1
