package bw.agriconnect.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.data.remote.dto.VersionManifestDto
import bw.agriconnect.data.update.ApkDownloader
import bw.agriconnect.data.update.ApkInstaller
import bw.agriconnect.data.update.ApkVerifier
import bw.agriconnect.data.update.InstallStatus
import bw.agriconnect.data.update.UpdateChecker
import bw.agriconnect.data.update.UpdateDecision
import bw.agriconnect.data.update.UpdateException
import bw.agriconnect.data.update.manifestOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Where the one active update attempt is. Lives in a ViewModel (scoped to the
 * navigation host via `hiltViewModel()` in [bw.agriconnect.ui.AgriConnectRoot])
 * so neither recomposition, navigation, nor a configuration change resets an
 * in-flight download.
 *
 * Idle -> (Update now) -> Downloading -> Verifying -> Installing -> Installed
 *   \\-> NeedsInstallPermission (Android 8+, explained *before* the settings
 *       screen opens) -> Downloading ...
 * Any step may land in Failed; Cancel during Downloading returns to Idle with
 * the prompt dialog still shown.
 */
sealed interface UpdateFlow {
    /** No attempt running; the prompt (if any) decides what shows. */
    data object Idle : UpdateFlow

    /** Explanation for the one-time "install unknown apps" grant, shown first. */
    data object NeedsInstallPermission : UpdateFlow

    /** [fraction] is null while the response declares no total length. */
    data class Downloading(val fraction: Float?) : UpdateFlow

    data object Verifying : UpdateFlow
    data object Installing : UpdateFlow

    /** Terminal error with a user-safe message; dismiss returns to the prompt. */
    data class Failed(val message: String) : UpdateFlow

    data object Installed : UpdateFlow
}

/**
 * Drives the update dialogs and the download/verify/install pipeline. All
 * Android-facing work happens here so the composables stay declarative; every
 * failure mode (network, storage, verification, install) ends as
 * [UpdateFlow.Failed] with copy the user can act on — except cancellation,
 * which returns quietly to the prompt.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: ApkInstaller,
) : ViewModel() {

    /** The dialog decision from the last successful check (survives recomposition). */
    val prompt: StateFlow<UpdateDecision?> = checker.prompt

    private val _flow = MutableStateFlow<UpdateFlow>(UpdateFlow.Idle)
    val flow: StateFlow<UpdateFlow> = _flow.asStateFlow()

    private var updateJob: Job? = null

    /** "Update later": remember this version code so it is not re-offered. */
    fun onLater() {
        checker.dismissPrompt()
        _flow.value = UpdateFlow.Idle
    }

    fun onUpdateNow() {
        if (updateJob?.isActive == true) return
        if (needsInstallPermission()) {
            // Shown only at the moment of install, with an explanation first —
            // never requested at startup, never silently bypassed.
            _flow.value = UpdateFlow.NeedsInstallPermission
        } else {
            startUpdate()
        }
    }

    fun onPermissionNotNow() {
        _flow.value = UpdateFlow.Idle
    }

    /** The system screen that grants THIS app the right to install APKs. */
    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Result of the settings round-trip: continue only when the grant landed. */
    fun onInstallPermissionResult() {
        if (needsInstallPermission()) {
            _flow.value = UpdateFlow.Failed(
                "Permission to install updates was not granted, so nothing was installed.",
            )
        } else {
            startUpdate()
        }
    }

    /** Cancels an in-flight download (aborts the socket at once) quietly. */
    fun onCancel() {
        downloader.cancelActive()
        updateJob?.cancel()
    }

    /** Dismisses a failure dialog; the prompt (if still current) reappears. */
    fun onDismissResult() {
        _flow.value = UpdateFlow.Idle
    }

    /** After a successful install: drop the prompt without recording "later". */
    fun onInstalledDismissed() {
        checker.clearPrompt()
        _flow.value = UpdateFlow.Idle
    }

    private fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    private fun startUpdate() {
        val manifest: VersionManifestDto = prompt.value?.manifestOrNull
            ?: run { _flow.value = UpdateFlow.Idle; return }
        if (updateJob?.isActive == true) return

        updateJob = viewModelScope.launch {
            var apk: File? = null
            try {
                _flow.value = UpdateFlow.Downloading(null)
                val download = downloader.download(manifest) { fraction ->
                    val current = _flow.value
                    if (current is UpdateFlow.Downloading) {
                        _flow.value = UpdateFlow.Downloading(fraction)
                    }
                }
                apk = download.file

                _flow.value = UpdateFlow.Verifying
                verifier.verify(download, manifest)

                _flow.value = UpdateFlow.Installing
                when (val status = installer.install(download.file)) {
                    InstallStatus.Success -> {
                        // The session already consumed the file.
                        runCatching { download.file.delete() }
                        _flow.value = UpdateFlow.Installed
                    }

                    // User backed out of the system prompt, or the fallback
                    // installer owns the flow now: return to the prompt quietly.
                    InstallStatus.Aborted, InstallStatus.AwaitingSystemInstaller ->
                        _flow.value = UpdateFlow.Idle

                    is InstallStatus.Failure -> {
                        runCatching { download.file.delete() }
                        _flow.value = UpdateFlow.Failed(status.message)
                    }

                    else -> _flow.value = UpdateFlow.Failed(
                        "The update did not complete. Please try again.",
                    )
                }
            } catch (e: CancellationException) {
                // User cancelled: the downloader already deleted the partial
                // file; show the prompt again, never an error.
                _flow.value = UpdateFlow.Idle
                throw e
            } catch (e: UpdateException) {
                runCatching { apk?.delete() }
                _flow.value = UpdateFlow.Failed(
                    e.message ?: "The update could not be completed. Please try again.",
                )
            } catch (e: Exception) {
                runCatching { apk?.delete() }
                _flow.value = UpdateFlow.Failed(
                    "Something went wrong while updating. Please try again.",
                )
            }
        }
    }
}
