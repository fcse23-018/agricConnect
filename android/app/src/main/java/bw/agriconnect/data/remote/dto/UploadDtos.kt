package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Response of `POST /uploads` (backend `modules/uploads/uploads.routes.js`).
 *
 * [url] is an origin-relative path (`/uploads/<uuid>.<ext>`), not an absolute URL: the
 * caller stores it on the record it is editing and the client resolves it against the
 * API origin when rendering (see `bw.agriconnect.core.util.ImageUrls`).
 */
@Serializable
data class UploadResponse(val url: String)
