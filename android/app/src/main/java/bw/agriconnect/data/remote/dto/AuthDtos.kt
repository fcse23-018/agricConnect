package bw.agriconnect.data.remote.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Wire types for authentication endpoints (docs/02-API-CONTRACT.md §2.1).
 *
 * They are kept dumb: plain data with defaults so a newer server can add fields
 * without breaking an older client (`ignoreUnknownKeys` on the shared `Json`).
 * Enums travel as strings; conversion to domain enums happens in `Mappers.kt`
 * with a defensive fallback so an unknown value can never crash the app.
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RegisterRequest(
    val fullName: String,
    val phoneNumber: String,
    val email: String? = null,
    val password: String,
    /** One of `FARMER`, `FARM_EMPLOYEE`, `BUYER`. Never a privileged flag. */
    val role: String,
    /**
     * Consent flags are part of the register contract and must always reach the
     * server, so they are encoded even when they hold their default value
     * (the shared `Json` does not set `encodeDefaults`).
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val acceptTerms: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val acceptPrivacy: Boolean = true,
)

@Serializable
data class LoginRequest(
    /** Phone number or email, per the contract's `identifier` field. */
    val identifier: String,
    val password: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String? = null)

@Serializable
data class ForgotPasswordRequest(val identifier: String)

/** Shared `{identifier}` body for endpoints that only need the account handle. */
@Serializable
data class IdentifierRequest(val identifier: String)

@Serializable
data class VerifyCodeRequest(
    val identifier: String,
    val code: String,
)

@Serializable
data class ResetPasswordRequest(
    val resetToken: String,
    val newPassword: String,
)

/** `201`/`200` body for register and login. */
@Serializable
data class AuthResponse(
    val user: UserDto,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    /**
     * Only ever populated by non-production backends (dev outbox) so a developer
     * can complete verification without a real SMS provider. Treated as optional
     * and never logged.
     */
    val devVerificationCode: String? = null,
)

/** `200` body for `/auth/refresh`. */
@Serializable
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

/** `200` body for `/auth/password/verify`. */
@Serializable
data class ResetTokenResponse(val resetToken: String)

/** Generic `{ "message": "..." }` acknowledgement. */
@Serializable
data class MessageResponse(val message: String)

/** `GET /auth/me` returns the user nested under `user`. */
@Serializable
data class MeResponse(val user: UserDto)
