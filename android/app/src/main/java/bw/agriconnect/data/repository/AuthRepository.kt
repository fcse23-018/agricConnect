package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.core.util.FormValidator
import bw.agriconnect.core.util.FormValidator.normalisePhone
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.ChangePasswordRequest
import bw.agriconnect.data.remote.dto.ForgotPasswordRequest
import bw.agriconnect.data.remote.dto.IdentifierRequest
import bw.agriconnect.data.remote.dto.LoginRequest
import bw.agriconnect.data.remote.dto.LogoutRequest
import bw.agriconnect.data.remote.dto.RegisterRequest
import bw.agriconnect.data.remote.dto.ResetPasswordRequest
import bw.agriconnect.data.remote.dto.UpdateProfileRequest
import bw.agriconnect.data.remote.dto.VerifyCodeRequest
import bw.agriconnect.data.remote.dto.toAuthTokens
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.data.session.AuthTokens
import bw.agriconnect.data.session.SessionManager
import bw.agriconnect.data.session.TokenStore
import bw.agriconnect.domain.model.Session
import bw.agriconnect.domain.model.User
import bw.agriconnect.domain.model.UserRole
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth repository - offline-first safe for reads of cached identity via SessionManager,
 * network-first for auth operations (register/login/refresh/logout/password).
 */
interface AuthRepository {
    val session: StateFlow<Session?>
    val isLoggedIn: Boolean

    suspend fun restoreSession(): Session?

    suspend fun register(
        fullName: String,
        phoneNumber: String,
        email: String? = null,
        password: String,
        role: UserRole,
        acceptTerms: Boolean = true,
        acceptPrivacy: Boolean = true,
    ): AppResult<Pair<User, AuthTokens>>

    suspend fun login(identifier: String, password: String): AppResult<Pair<User, AuthTokens>>

    suspend fun logout(): AppResult<Unit>
    suspend fun logoutAll(): AppResult<Unit>

    suspend fun refresh(): AppResult<AuthTokens>

    suspend fun getCurrentUser(): AppResult<User>

    suspend fun updateProfile(
        fullName: String? = null,
        email: String? = null,
    ): AppResult<User>

    suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<Unit>

    suspend fun requestPasswordReset(identifier: String): AppResult<Unit>
    suspend fun verifyPasswordResetCode(identifier: String, code: String): AppResult<String>
    suspend fun resetPassword(resetToken: String, newPassword: String): AppResult<Unit>

    suspend fun requestVerificationCode(identifier: String): AppResult<Unit>
    suspend fun confirmVerification(identifier: String, code: String): AppResult<Unit>
}

@Singleton
internal class DefaultAuthRepository @Inject constructor(
    private val api: ApiService,
    private val sessionManager: SessionManager,
    private val tokenStore: TokenStore,
    private val errorParser: NetworkErrorParser,
    private val json: Json,
) : AuthRepository {

    override val session: StateFlow<Session?> = sessionManager.session
    override val isLoggedIn: Boolean get() = sessionManager.isLoggedIn

    override suspend fun restoreSession(): Session? = sessionManager.restore()

    override suspend fun register(
        fullName: String,
        phoneNumber: String,
        email: String?,
        password: String,
        role: UserRole,
        acceptTerms: Boolean,
        acceptPrivacy: Boolean,
    ): AppResult<Pair<User, AuthTokens>> {
        val nameErr = FormValidator.validateFullName(fullName)
        if (nameErr != null) return AppResult.Failure(validationError(nameErr, mapOf("fullName" to nameErr)))
        val phoneErr = FormValidator.validatePhone(phoneNumber)
        if (phoneErr != null) {
            val fields = mapOf("phoneNumber" to phoneErr)
            return AppResult.Failure(validationError(phoneErr, fields))
        }
        val passErr = FormValidator.validatePassword(password)
        if (passErr != null) {
            val fields = mapOf("password" to passErr)
            return AppResult.Failure(validationError(passErr, fields))
        }
        val emailErr = FormValidator.validateEmail(email.orEmpty(), required = false)
        if (emailErr != null && !email.isNullOrBlank()) {
            return AppResult.Failure(validationError(emailErr, mapOf("email" to emailErr)))
        }
        val body = RegisterRequest(
            fullName = fullName.trim(),
            phoneNumber = normalisePhone(phoneNumber),
            email = email?.trim()?.takeIf { it.isNotBlank() },
            password = password,
            role = role.name,
            acceptTerms = acceptTerms,
            acceptPrivacy = acceptPrivacy,
        )
        val idempotency = "reg-" + UUID.randomUUID().toString()
        return runCatchingApi(errorParser::parse) {
            val resp = api.register(idempotencyKey = idempotency, body = body)
            val user = resp.user.toDomain()
            val tokens = resp.toAuthTokens()
            sessionManager.signIn(
                Session(userId = user.userId, role = user.role, fullName = user.fullName),
                tokens,
            )
            user to tokens
        }
    }

    override suspend fun login(identifier: String, password: String): AppResult<Pair<User, AuthTokens>> {
        val body = LoginRequest(identifier = identifier.trim(), password = password)
        return runCatchingApi(errorParser::parse) {
            val resp = api.login(body)
            val user = resp.user.toDomain()
            val tokens = resp.toAuthTokens()
            sessionManager.signIn(
                Session(userId = user.userId, role = user.role, fullName = user.fullName),
                tokens,
            )
            user to tokens
        }
    }

    override suspend fun logout(): AppResult<Unit> {
        val rt = tokenStore.refreshToken()
        val body = LogoutRequest(refreshToken = rt)
        val result = runCatchingApi(errorParser::parse) {
            val resp = api.logout(body)
            if (resp.isSuccessful) {
                Unit
            } else {
                throw retrofit2.HttpException(resp)
            }
        }
        // Server-side revocation is best-effort, but the local session always ends:
        // otherwise "Sign out" would navigate to the login form while a cold start
        // restored the old session and signed the user straight back in.
        sessionManager.signOut()
        return result
    }

    override suspend fun logoutAll(): AppResult<Unit> {
        val result = runCatchingApi(errorParser::parse) {
            val resp = api.logoutAll()
            if (resp.isSuccessful) {
                Unit
            } else {
                throw retrofit2.HttpException(resp)
            }
        }
        // Best-effort server revocation, guaranteed local sign-out — see logout().
        sessionManager.signOut()
        return result
    }

    override suspend fun refresh(): AppResult<AuthTokens> {
        val rt = tokenStore.refreshToken() ?: return AppResult.Failure(
            AppError(AppError.UNAUTHENTICATED, "No refresh token available.")
        )
        return runCatchingApi(errorParser::parse) {
            val resp = api.refresh(bw.agriconnect.data.remote.dto.RefreshRequest(rt))
            resp.toAuthTokens().also { tokens -> sessionManager.signIn(session.value ?: Session("", bw.agriconnect.domain.model.UserRole.BUYER, ""), tokens) }
        }
    }

    override suspend fun getCurrentUser(): AppResult<User> {
        return runCatchingApi(errorParser::parse) {
            val resp = api.me()
            val user = resp.user.toDomain()
            sessionManager.updateUser(user)
            user
        }
    }

    override suspend fun updateProfile(fullName: String?, email: String?): AppResult<User> {
        val body = UpdateProfileRequest(fullName = fullName?.trim()?.takeIf { it.isNotBlank() }, email = email?.trim()?.takeIf { it.isNotBlank() })
        return runCatchingApi(errorParser::parse) {
            val dto = api.patchMe(body).user
            dto.toDomain().also { sessionManager.updateUser(it) }
        }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<Unit> {
        val passErr = FormValidator.validatePassword(newPassword)
        if (passErr != null) return AppResult.Failure(validationError(passErr, mapOf("newPassword" to passErr)))
        val body = ChangePasswordRequest(currentPassword = currentPassword, newPassword = newPassword)
        return runCatchingApi(errorParser::parse) {
            api.changePassword(body)
            Unit
        }
    }

    override suspend fun requestPasswordReset(identifier: String): AppResult<Unit> {
        return runCatchingApi(errorParser::parse) {
            api.passwordForgot(ForgotPasswordRequest(identifier.trim()))
            Unit
        }
    }

    override suspend fun verifyPasswordResetCode(identifier: String, code: String): AppResult<String> {
        return runCatchingApi(errorParser::parse) {
            val resp = api.passwordVerify(VerifyCodeRequest(identifier.trim(), code.trim()))
            resp.resetToken
        }
    }

    override suspend fun resetPassword(resetToken: String, newPassword: String): AppResult<Unit> {
        val passErr = FormValidator.validatePassword(newPassword)
        if (passErr != null) return AppResult.Failure(validationError(passErr, mapOf("newPassword" to passErr)))
        return runCatchingApi(errorParser::parse) {
            api.passwordReset(ResetPasswordRequest(resetToken = resetToken, newPassword = newPassword))
            Unit
        }
    }

    override suspend fun requestVerificationCode(identifier: String): AppResult<Unit> {
        return runCatchingApi(errorParser::parse) {
            api.verificationRequest(IdentifierRequest(identifier.trim()))
            Unit
        }
    }

    override suspend fun confirmVerification(identifier: String, code: String): AppResult<Unit> {
        return runCatchingApi(errorParser::parse) {
            api.verificationConfirm(VerifyCodeRequest(identifier.trim(), code.trim()))
            Unit
        }
    }
}
