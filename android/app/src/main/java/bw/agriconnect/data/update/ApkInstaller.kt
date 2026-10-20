package bw.agriconnect.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.os.Build
import androidx.core.content.FileProvider
import bw.agriconnect.BuildConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verdict of one install attempt, reported back from
 * [UpdateInstallReceiver] (PackageInstaller path) or produced synchronously.
 */
sealed interface InstallStatus {
    /** Nothing in flight. */
    data object Idle : InstallStatus

    /** Session committed; waiting for the system's verdict. */
    data object Committing : InstallStatus

    /** Legacy fallback handed the APK to the system installer UI. */
    data object AwaitingSystemInstaller : InstallStatus

    /** The system installed the update successfully. */
    data object Success : InstallStatus

    /** The user dismissed the system confirmation prompt. */
    data object Aborted : InstallStatus

    /** Install failed; [message] is user-safe and actionable. */
    data class Failure(val message: String) : InstallStatus
}

/**
 * Hilt access for [UpdateInstallReceiver], which the system constructs with a
 * no-arg constructor and therefore cannot inject.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateInstallEntryPoint {
    fun apkInstaller(): ApkInstaller
}

/**
 * Installs a verified APK.
 *
 * Primary path — a [PackageInstaller] session (API 21+): the APK is *streamed*
 * into the session (no world-readable staging file, proper system progress
 * UI), and the commit callback drives [InstallStatus]:
 *  - `STATUS_PENDING_USER_ACTION` launches the system confirmation intent the
 *    system attached (never bypassed — Android's install security always gets
 *    the final say);
 *  - `STATUS_SUCCESS` / `STATUS_FAILURE*` land through [reportStatus].
 *
 * Fallback path — if a session cannot be created or committed (OEM quirks),
 * the file is handed to the system installer via the dedicated FileProvider
 * (`${applicationId}.fileprovider.updates`) and [InstallStatus.AwaitingSystemInstaller]
 * is returned immediately.
 *
 * Because the archive has already been verified to carry the same
 * applicationId and signing key (see [ApkVerifier]), both paths are a normal
 * in-place upgrade: Android preserves all app data; no backup tricks are used.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val _status = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val status: StateFlow<InstallStatus> = _status.asStateFlow()

    /**
     * Commits [file] to the system and suspends until a terminal status
     * arrives (or [INSTALL_TIMEOUT_MILLIS] elapses). Never throws for install
     * failures — the verdict is the returned [InstallStatus].
     */
    suspend fun install(file: File): InstallStatus {
        _status.value = InstallStatus.Committing

        val committed = try {
            commitSession(file)
            true
        } catch (e: Exception) {
            false
        }
        if (!committed) return startSystemInstaller(file)

        val terminal = withTimeoutOrNull(INSTALL_TIMEOUT_MILLIS) {
            _status.first { it != InstallStatus.Committing }
        } ?: InstallStatus.Failure(
            "The system installer did not respond in time. Please try again.",
        )
        _status.value = terminal
        return terminal
    }

    /** Called by [UpdateInstallReceiver] with the system's verdict. */
    fun reportStatus(status: InstallStatus) {
        _status.value = status
    }

    /** Streams the file into a full-install session pinned to our package. */
    private fun commitSession(file: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            // Pinning the session to our own package makes the system treat it
            // as an update of exactly this app; combined with the identical
            // applicationId + signing key checked in ApkVerifier, the result is
            // a data-preserving in-place upgrade.
            setAppPackageName(context.packageName)
            setSize(file.length())
        }

        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("update", 0, file.length()).use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }

                // The system fills EXTRA_STATUS/EXTRA_INTENT into this intent,
                // so on API 31+ the PendingIntent must be mutable; the
                // receiver is explicit and not exported.
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * Documented fallback: let the system's own installer open the file
     * through the dedicated update FileProvider. No `FLAG_ACTIVITY_NEW_TASK`
     * shortcut around Android's confirmation — the system UI still asks.
     */
    private fun startSystemInstaller(file: File): InstallStatus = try {
        val uri = FileProvider.getUriForFile(context, UPDATE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        _status.value = InstallStatus.AwaitingSystemInstaller
        InstallStatus.AwaitingSystemInstaller
    } catch (e: Exception) {
        val failure = InstallStatus.Failure(
            "No app on this device can install the update (${e.message ?: "unavailable"}).",
        )
        _status.value = failure
        failure
    }

    companion object {
        /** Long enough for a user to read and answer the system prompt. */
        private const val INSTALL_TIMEOUT_MILLIS = 10L * 60L * 1000L

        private const val APK_MIME = "application/vnd.android.package-archive"

        /**
         * Must match the `<provider>` authority in AndroidManifest.xml and the
         * `file_paths_updates.xml` root (cache/updates/). Built from
         * BuildConfig.APPLICATION_ID so debug builds (".debug" suffix) resolve
         * to the same authority the manifest declares.
         */
        val UPDATE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider.updates"
    }
}
