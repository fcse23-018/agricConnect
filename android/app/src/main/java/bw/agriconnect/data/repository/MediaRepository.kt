package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place the app uploads an image.
 *
 * `POST /uploads` stores a single file through the shared backend pipeline
 * (jpeg/png/webp, size cap, UUID name) and returns an origin-relative path such as
 * `/uploads/<uuid>.jpg`. The caller then attaches that path to whatever record it is
 * editing — a produce listing's `imageUrl`, a farm plot's `imageUrl` — and the client
 * resolves it against the API origin when rendering
 * ([bw.agriconnect.core.util.ImageUrls]).
 *
 * Profile photos are the exception: `POST /users/me/avatar` attaches the image to the
 * caller's own account in the same request, so it lives on `AccountRepository`.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val api: ApiService,
    private val errorParser: NetworkErrorParser,
) {

    /** Uploads [bytes] and returns the stored path, or a presentation-safe failure. */
    suspend fun uploadImage(bytes: ByteArray, mimeType: String, fileName: String): AppResult<String> =
        runCatchingApi(errorParser::parse) {
            val body = bytes.toRequestBody(mimeType.toMediaType())
            val part = MultipartBody.Part.createFormData("image", fileName, body)
            api.uploadImage(part).url
        }
}
