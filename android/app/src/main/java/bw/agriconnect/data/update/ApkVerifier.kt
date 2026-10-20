package bw.agriconnect.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import bw.agriconnect.data.remote.dto.VersionManifestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies a downloaded APK before it is ever handed to the installer.
 * Every failed check throws [UpdateException] with a user-safe message; the
 * caller deletes the file. The four checks, in order:
 *
 *  1. **SHA-256** — matches `checksumSha256` when the manifest published one
 *     (`checksumMatches` treats an absent checksum as "not enforced"; the
 *     remaining three checks still apply).
 *  2. **Package name** — `PackageManager.getPackageArchiveInfo` must report the
 *     *installed* applicationId (`bw.agriconnect`, or `.debug` for debug
 *     builds). Comparing against the installed id — not just the manifest's
 *     `packageName` string — is what guarantees the file can be an in-place
 *     upgrade of THIS app at all.
 *  3. **Version code** — must equal `latestVersionCode` exactly, so a stale or
 *     swapped file at the same URL cannot be installed.
 *  4. **Signing certificates** — the archive's signers must match the installed
 *     app's signing certs (including past certs after key rotation). Same
 *     applicationId + same signing key is also what makes the upgrade
 *     data-preserving: Android performs a normal in-place upgrade, no backup
 *     tricks are attempted.
 */
@Singleton
class ApkVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Throws [UpdateException] when any check fails; returns normally otherwise. */
    fun verify(download: ApkDownload, manifest: VersionManifestDto) {
        if (!checksumMatches(manifest.checksumSha256, download.sha256Hex)) {
            throw UpdateException(
                "The downloaded update did not match its published checksum, so it was discarded.",
            )
        }

        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(download.file.absolutePath, archiveFlags())
            ?: throw UpdateException("The downloaded file could not be read as an APK.")

        if (archive.packageName != context.packageName) {
            throw UpdateException(
                "The downloaded package (${archive.packageName ?: "unknown"}) does not " +
                    "match this app (${context.packageName}), so it was not installed.",
            )
        }

        val archiveVersion = PackageInfoCompat.getLongVersionCode(archive)
        if (archiveVersion != manifest.latestVersionCode.toLong()) {
            throw UpdateException(
                "The downloaded APK is build $archiveVersion but the release is " +
                    "${manifest.latestVersionCode}, so it was not installed.",
            )
        }

        val archiveCerts = archiveCertDigests(archive)
        if (archiveCerts.isEmpty()) {
            throw UpdateException("The downloaded APK carries no signing certificate and was not installed.")
        }
        val installedCerts = installedCertDigests(pm)
        if (archiveCerts.none { it in installedCerts }) {
            throw UpdateException(
                "The downloaded APK is not signed with this app's key, so it was not installed.",
            )
        }
    }

    /** On API 28+ signing info comes from GET_SIGNING_CERTIFICATES, older via GET_SIGNATURES. */
    @Suppress("DEPRECATION")
    private fun archiveFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    /** SHA-256 hex of every certificate the downloaded archive is signed with. */
    @Suppress("DEPRECATION")
    private fun archiveCertDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            info.signatures ?: emptyArray()
        }
        return signers.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    /**
     * SHA-256 hex of the installed app's current signers, plus the signing
     * history when the app has rotated its key (API 28+), so a legitimately
     * re-signed release still verifies against the installed package.
     */
    @Suppress("DEPRECATION")
    private fun installedCertDigests(pm: PackageManager): Set<String> {
        val digests = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pm
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?: return emptySet()
            signingInfo.apkContentsSigners?.forEach { digests += sha256Hex(it.toByteArray()) }
            if (!signingInfo.hasMultipleSigners() && signingInfo.hasPastSigningCertificates()) {
                @Suppress("DEPRECATION")
                val history = signingInfo.signingCertificateHistory
                history?.forEach { digests += sha256Hex(it.toByteArray()) }
            }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.forEach { digests += sha256Hex(it.toByteArray()) }
        }
        return digests
    }
}
