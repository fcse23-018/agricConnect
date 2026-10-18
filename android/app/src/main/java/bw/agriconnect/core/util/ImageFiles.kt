package bw.agriconnect.core.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a user-picked image from a content URI into the bytes the upload endpoints
 * expect.
 *
 * This lives outside the ViewModels on purpose: they stay free of any `Context`, which
 * keeps them unit-testable. The validation mirrors the server's rules
 * (`backend/src/services/uploads.js`) so a bad pick fails fast with an actionable
 * message instead of a round-trip.
 */
object ImageFiles {

    /** Backend hard cap (`MAX_UPLOAD_BYTES`, default 2 MB). */
    const val MAX_BYTES: Long = 2L * 1024 * 1024

    private val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/webp")

    /** The bytes, MIME type and a safe file name for one picked image. */
    data class Picked(val bytes: ByteArray, val mimeType: String, val fileName: String) {
        // ByteArray has identity equality; the data class is a carrier, not a value.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Reads and validates [uri]; the failure message is safe to show to the user. */
    suspend fun read(context: Context, uri: Uri): Result<Picked> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val declared = resolver.getType(uri)
            val mime = when {
                declared == null -> "image/jpeg"
                declared in ALLOWED_MIME -> declared
                else -> error("Only JPEG, PNG or WebP images are supported.")
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read the selected image.")
            if (bytes.isEmpty()) error("The selected image is empty.")
            if (bytes.size > MAX_BYTES) error("Images must be 2 MB or smaller.")
            Picked(bytes = bytes, mimeType = mime, fileName = "upload.${extensionFor(mime)}")
        }
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
