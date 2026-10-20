package bw.agriconnect.data.update

import android.content.Context
import android.os.StatFs
import bw.agriconnect.data.remote.dto.VersionManifestDto
import bw.agriconnect.di.ApkDownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A verified-download candidate: where it landed and its computed SHA-256. */
data class ApkDownload(val file: File, val sha256Hex: String)

/**
 * Streams the release APK from the manifest's `downloadUrl` into app-private
 * storage, defending the whole way:
 *
 *  - **HTTPS only** — a non-HTTPS URL is rejected before any request is made
 *    (the server enforces HTTPS as well; this is the client's own check).
 *  - **Free-space pre-check** (`StatFs`) against the manifest's `sizeBytes`
 *    and the response's `Content-Length`, before a single byte is written.
 *  - **Streaming size guard** — the file is never allowed to exceed the
 *    expected size by more than 10 % (or 256 MB when nothing is declared), so
 *    a malicious or broken server cannot fill the disk.
 *  - **SHA-256 while streaming** — the digest is computed in the same pass
 *    and returned for `ApkVerifier` to compare with `checksumSha256`.
 *  - **App-private storage** — `cacheDir/updates/`, which is also what the
 *    dedicated FileProvider (`file_paths_updates.xml`) exposes for install.
 *
 * The HTTP client is the bare [ApkDownloadClient] so the bearer token is never
 * sent to a third-party download host. Failures surface as [UpdateException]
 * with a user-safe message; coroutine cancellation deletes the partial file
 * and propagates as `CancellationException`.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApkDownloadClient private val client: OkHttpClient,
) {

    /** In-flight call, so the UI's Cancel button aborts a blocked read at once. */
    @Volatile
    private var activeCall: Call? = null

    /**
     * Downloads the APK for [manifest] and returns it with its SHA-256 hex.
     * [onProgress] receives `0f..1f` when a total size is known, and simply
     * stops being called when it is not (the dialog stays indeterminate).
     */
    suspend fun download(
        manifest: VersionManifestDto,
        onProgress: (Float?) -> Unit,
    ): ApkDownload = withContext(Dispatchers.IO) {
        val url = manifest.downloadUrl
        if (url.isNullOrBlank() || !url.startsWith("https://", ignoreCase = true)) {
            throw UpdateException(
                "The update link is not a secure (HTTPS) address, so nothing was downloaded.",
            )
        }
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw UpdateException("The update link is not a usable URL, so nothing was downloaded.")

        val dir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        val target = File(dir, UPDATE_FILE_NAME)
        target.delete()

        // Storage pre-check when the manifest declares a size; otherwise the
        // response's Content-Length covers it below, before the first write.
        manifest.sizeBytes
            ?.takeIf { it > 0 }
            ?.let { ensureFreeSpace(dir, it) }

        val call = client.newCall(Request.Builder().url(httpUrl).build())
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException(
                        "The update download failed (server answered ${response.code}).",
                    )
                }
                val body = response.body
                    ?: throw UpdateException("The update download returned an empty response.")

                val declaredLength = body.contentLength()
                if (declaredLength > 0) ensureFreeSpace(dir, declaredLength)
                val limit = downloadLimit(manifest.sizeBytes, declaredLength)

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            // Makes a Cancel button responsive even while a
                            // slow read is blocked in native code.
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > limit) {
                                throw UpdateException(
                                    "The download is larger than the published update, so it was cancelled.",
                                )
                            }
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (declaredLength > 0) {
                                val percent = ((total * 100) / declaredLength).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                        out.fd.sync()
                    }
                }
                if (total <= 0L) throw UpdateException("The update download was empty.")
                onProgress(1f)
                ApkDownload(target, digest.digest().toHexLower())
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: UpdateException) {
            target.delete()
            throw e
        } catch (e: IOException) {
            target.delete()
            if (call.isCanceled()) throw CancellationException("The update download was cancelled.")
            // Network failure mid-download (or HTTP/2 stream reset): report it
            // once, clearly — the user may retry.
            throw UpdateException(
                "The update download was interrupted. Check your connection and try again.",
            )
        } finally {
            activeCall = null
        }
    }

    /** Aborts an in-flight download immediately (UI Cancel action). */
    fun cancelActive() {
        activeCall?.cancel()
    }

    /**
     * Refuses the download before it starts when the device cannot hold the
     * expected size plus a small headroom for the rest of the system.
     */
    private fun ensureFreeSpace(dir: File, neededBytes: Long) {
        val stat = StatFs(dir.path)
        val free = stat.availableBlocksLong * stat.blockSizeLong
        if (free < neededBytes + STORAGE_HEADROOM_BYTES) {
            val neededMb = (neededBytes / BYTES_PER_MB) + 1
            throw UpdateException(
                "There is not enough free storage to download the update " +
                    "(about $neededMb MB needed). Free some space and try again.",
            )
        }
    }

    /**
     * Hard cap on how many bytes may be written: the larger of the manifest
     * size and the response length, tolerated up to 10 % slack (both sources
     * may be slightly stale), and never more than [MAX_APK_BYTES].
     */
    private fun downloadLimit(sizeBytes: Long?, declaredLength: Long): Long {
        val expected = maxOf(
            sizeBytes ?: 0L,
            if (declaredLength > 0) declaredLength else 0L,
        )
        if (expected <= 0L) return MAX_APK_BYTES
        return minOf(expected + expected / 10 + 1, MAX_APK_BYTES)
    }

    companion object {
        /** App-private download location; also the FileProvider root. */
        const val UPDATES_DIR = "updates"
        const val UPDATE_FILE_NAME = "update.apk"

        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private const val STORAGE_HEADROOM_BYTES = 16L * 1024 * 1024
        private const val BYTES_PER_MB = 1024L * 1024
    }
}
