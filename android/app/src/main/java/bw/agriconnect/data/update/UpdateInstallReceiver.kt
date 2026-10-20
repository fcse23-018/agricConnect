package bw.agriconnect.data.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dagger.hilt.android.EntryPointAccessors

/**
 * Receives PackageInstaller session results (declared in AndroidManifest.xml,
 * `exported="false"` — only our own PendingIntent at commit time can invoke
 * it) and forwards them to the singleton [ApkInstaller].
 *
 * Two cases deserve care:
 *  - `STATUS_PENDING_USER_ACTION`: the system attached its own confirmation
 *    intent under `EXTRA_INTENT`; we launch it verbatim. This is how Android's
 *    install security is *honoured*, never bypassed.
 *  - failures: translated to a user-safe message and reported, never
 *    swallowed — the update dialog shows them.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val statusCode = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (statusCode) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Log.w(TAG, "install needs user action but carried no confirmation intent")
                } else {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> installer(context)
                .reportStatus(InstallStatus.Success)

            PackageInstaller.STATUS_FAILURE_ABORTED -> installer(context)
                .reportStatus(InstallStatus.Aborted)

            else -> {
                // Raw system text is log-only; the user sees friendly copy.
                Log.w(
                    TAG,
                    "install failed (status $statusCode): " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
                installer(context).reportStatus(
                    InstallStatus.Failure(friendlyMessage(statusCode)),
                )
            }
        }
    }

    private fun installer(context: Context): ApkInstaller =
        EntryPointAccessors
            .fromApplication(context.applicationContext, UpdateInstallEntryPoint::class.java)
            .apkInstaller()

    private fun friendlyMessage(statusCode: Int): String = when (statusCode) {
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "There is not enough storage to install the update. Free some space and try again."

        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Android blocked the install. Check this app's install permissions and try again."

        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The update package was rejected as invalid."

        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The update conflicts with the installed app."

        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "The update is not compatible with this device."

        else -> "The update could not be installed (status $statusCode)."
    }

    private companion object {
        const val TAG = "AgriConnectUpdate"
    }
}
