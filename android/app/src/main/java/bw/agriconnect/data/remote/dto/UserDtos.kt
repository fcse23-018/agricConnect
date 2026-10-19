package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the profile, notification-preference and account-deletion
 * surfaces (docs/02-API-CONTRACT.md §2.2 and §2.3).
 */

@Serializable
data class UserDto(
    val userId: String,
    val fullName: String,
    val phoneNumber: String,
    val email: String? = null,
    /** `FARM_OWNER` | `FARMER` | `BUYER`; the server's value, never client-chosen. */
    val role: String,
    /** `REGISTERED` | `UNVERIFIED` | `VERIFIED` | `SUSPENDED` | `PENDING_DELETION` | `DELETED`. */
    val accountStatus: String,
    val avatarUrl: String? = null,
    val notificationPreferences: NotificationPreferencesDto? = null,
    val verifiedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    /** Present for a FARMER once a farm owner has linked them (migration 005). */
    val employerFarmerId: String? = null,
)

@Serializable
data class NotificationPreferencesDto(
    val orders: Boolean = true,
    val sync: Boolean = true,
    val satellite: Boolean = true,
    val marketplace: Boolean = true,
)

/** `PATCH /users/me` — every field optional; `role` is never sent (server ignores it). */
@Serializable
data class UpdateProfileRequest(
    val fullName: String? = null,
    val email: String? = null,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/**
 * A FARMER links to exactly one employing FARM_OWNER (migration 005). The server
 * resolves the employer by *phone number* (`employerPhone`), so the body carries the
 * owner's phone handle — a farmer cannot supply an arbitrary owner id.
 */
@Serializable
data class EmployerLinkRequest(val employerPhone: String)

/**
 * The personal-data export is an open-ended document whose shape is owned by the
 * backend. Modelling it as [JsonObject] keeps the client forward-compatible and
 * lets the export screen render/Share the raw document without a bespoke type per
 * data category.
 */
typealias ExportEverythingDto = JsonObject

@Serializable
data class DeletionRequestBody(
    /** Re-authentication: the current password is required to start deletion. */
    val password: String,
    val reason: String? = null,
)

/** The store-required public deletion page takes a handle, not a password. */
@Serializable
data class PublicDeletionRequest(
    val identifier: String,
    val reason: String? = null,
)

@Serializable
data class DeletionRequestDto(
    val requestId: String,
    /** `PENDING` | `PROCESSING` | `COMPLETED` | `CANCELLED`. */
    val status: String,
    val requestedAt: String? = null,
    /** When the grace period ends and anonymisation runs. */
    val effectiveAt: String? = null,
    val retention: List<RetentionItemDto> = emptyList(),
)

@Serializable
data class RetentionItemDto(
    val dataCategory: String,
    val justification: String,
    val retainUntil: String? = null,
)

/**
 * `GET`/`POST .../cancel` on the deletion request return the request nested under
 * `request`; the key is explicitly `null` when no request exists (so the client can
 * distinguish "none pending" from a transport failure).
 */
@Serializable
data class DeletionRequestResponse(val request: DeletionRequestDto? = null)
