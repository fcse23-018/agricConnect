package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.DeletionRequestBody
import bw.agriconnect.data.remote.dto.EmployerLinkRequest
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.data.remote.dto.toDto
import bw.agriconnect.data.session.SessionManager
import bw.agriconnect.domain.model.DeletionRequest
import bw.agriconnect.domain.model.NotificationPreferences
import bw.agriconnect.domain.model.User
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Account-level operations that are not part of the core authentication lifecycle:
 * notification preferences, avatar upload, personal-data export, employer linking
 * and the account-deletion lifecycle.
 *
 * Every method here is network-only and failure-honest: none of them fabricate a
 * success when the device is offline. They wrap the call in [runCatchingApi] so a
 * transport failure surfaces as a presentation-safe [bw.agriconnect.core.result.AppError]
 * rather than an exception.
 */
interface AccountRepository {

    /** Replaces the server-held notification preferences and returns the fresh profile. */
    suspend fun updateNotificationPreferences(preferences: NotificationPreferences): AppResult<User>

    /** Uploads an avatar image (≤ 2 MB, jpeg/png/webp) and returns the fresh profile. */
    suspend fun uploadAvatar(bytes: ByteArray, mimeType: String, fileName: String): AppResult<User>

    /** Fetches the machine-readable personal-data export (shape owned by the backend). */
    suspend fun export(): AppResult<JsonObject>

    /** Links a FARM_EMPLOYEE to their employer by the owner's phone number. */
    suspend fun linkEmployer(employerPhone: String): AppResult<User>

    /** Starts the deletion grace period. Requires the current password (re-auth). */
    suspend fun requestAccountDeletion(password: String, reason: String? = null): AppResult<DeletionRequest>

    /** The current (possibly absent) deletion request. `null` means none is pending. */
    suspend fun getDeletionRequest(): AppResult<DeletionRequest?>

    /** Cancels a pending deletion request during the grace period. */
    suspend fun cancelAccountDeletion(): AppResult<DeletionRequest>

    /** Store-required public deletion entry point; always generic. */
    suspend fun requestPublicDeletion(identifier: String, reason: String? = null): AppResult<Unit>
}

@Singleton
internal class DefaultAccountRepository @Inject constructor(
    private val api: ApiService,
    private val errorParser: NetworkErrorParser,
    private val sessionManager: SessionManager,
) : AccountRepository {

    override suspend fun updateNotificationPreferences(
        preferences: NotificationPreferences,
    ): AppResult<User> = runCatchingApi(errorParser::parse) {
        val user = api.putNotificationPreferences(preferences.toDto()).user.toDomain()
        sessionManager.updateUser(user)
        user
    }

    override suspend fun uploadAvatar(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
    ): AppResult<User> = runCatchingApi(errorParser::parse) {
        val body = bytes.toRequestBody(mimeType.toMediaType())
        val part = MultipartBody.Part.createFormData("avatar", fileName, body)
        val user = api.uploadAvatar(part).user.toDomain()
        sessionManager.updateUser(user)
        user
    }

    override suspend fun export(): AppResult<JsonObject> =
        runCatchingApi(errorParser::parse) { api.export() }

    override suspend fun linkEmployer(employerPhone: String): AppResult<User> =
        runCatchingApi(errorParser::parse) {
            val user = api.employerLink(EmployerLinkRequest(employerPhone.trim())).user.toDomain()
            sessionManager.updateUser(user)
            user
        }

    override suspend fun requestAccountDeletion(
        password: String,
        reason: String?,
    ): AppResult<DeletionRequest> = runCatchingApi(errorParser::parse) {
        api.createDeletionRequest(
            DeletionRequestBody(password = password, reason = reason?.trim()?.takeIf { it.isNotBlank() }),
        ).toDomain()
    }

    override suspend fun getDeletionRequest(): AppResult<DeletionRequest?> =
        runCatchingApi(errorParser::parse) { api.getDeletionRequest().request?.toDomain() }

    override suspend fun cancelAccountDeletion(): AppResult<DeletionRequest> =
        runCatchingApi(errorParser::parse) {
            api.cancelDeletionRequest().request?.toDomain()
                ?: error("The server did not return the cancelled request.")
        }

    override suspend fun requestPublicDeletion(
        identifier: String,
        reason: String?,
    ): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        api.publicDeletionRequest(
            bw.agriconnect.data.remote.dto.PublicDeletionRequest(
                identifier = identifier.trim(),
                reason = reason?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        Unit
    }
}
