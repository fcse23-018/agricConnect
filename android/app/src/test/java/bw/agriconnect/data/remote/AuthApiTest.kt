package bw.agriconnect.data.remote

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.remote.dto.LoginRequest
import bw.agriconnect.data.remote.dto.RegisterRequest
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.AccountStatus
import bw.agriconnect.domain.model.UserRole
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Contract tests for the auth/register/login wire shape and for the error-envelope
 * mapping. They pin the exact paths, the idempotency header and the field-error
 * extraction so a refactor cannot silently change the API the server sees.
 */
class AuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `register posts the contract path, idempotency key and body`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(AUTH_RESPONSE),
        )

        val response = api.register(
            idempotencyKey = "tap-123",
            body = RegisterRequest(
                fullName = "Ada Mokoena",
                phoneNumber = "+26771234567",
                password = "s3cret-pass",
                role = "FARM_OWNER",
            ),
        )

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/auth/register")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("tap-123")

        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"fullName\":\"Ada Mokoena\"")
        assertThat(body).contains("\"role\":\"FARM_OWNER\"")
        assertThat(body).contains("\"acceptTerms\":true")
        // Nulls are omitted by explicitNulls = false.
        assertThat(body).doesNotContain("\"email\"")

        assertThat(response.accessToken).isEqualTo("access-1")
        assertThat(response.refreshToken).isEqualTo("refresh-1")
        assertThat(response.expiresIn).isEqualTo(900L)

        val user = response.user.toDomain()
        assertThat(user.fullName).isEqualTo("Ada Mokoena")
        assertThat(user.role).isEqualTo(UserRole.FARM_OWNER)
        assertThat(user.accountStatus).isEqualTo(AccountStatus.VERIFIED)
        assertThat(user.notificationPreferences.marketplace).isTrue()
    }

    @Test
    fun `login posts identifier and password and parses the auth response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(AUTH_RESPONSE),
        )

        val response = api.login(LoginRequest(identifier = "+26771234567", password = "s3cret-pass"))

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/auth/login")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("\"identifier\":\"+26771234567\"")
        assertThat(body).contains("\"password\":\"s3cret-pass\"")
        assertThat(response.user.userId).isEqualTo("user-1")
    }

    @Test
    fun `a 400 error envelope maps to an AppError carrying field errors`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(VALIDATION_ERROR),
        )

        val parser = NetworkErrorParser(json)
        val result = runCatchingApi(parser::parse) {
            api.login(LoginRequest(identifier = "x", password = "y"))
        }

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        val error = (result as AppResult.Failure).error
        assertThat(error.code).isEqualTo("VALIDATION_ERROR")
        assertThat(error.message).isEqualTo("Phone number is required. Password must be at least 6 characters.")
        assertThat(error.httpStatus).isEqualTo(400)
        assertThat(error.fieldErrors["phoneNumber"]).isEqualTo("Phone number is required.")
        assertThat(error.fieldErrors["password"]).isEqualTo("Password must be at least 6 characters.")
    }

    @Test
    fun `a generic validation message is replaced by the joined field errors`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "code": "VALIDATION_ERROR",
                        "message": "Please correct the highlighted fields.",
                        "details": [
                          { "field": "plotId", "message": "A valid plot identifier is required." }
                        ]
                      },
                      "requestId": "req-2"
                    }
                    """.trimIndent(),
                ),
        )

        val parser = NetworkErrorParser(json)
        val result = runCatchingApi(parser::parse) {
            api.latestNdvi("not-a-uuid")
        }

        val error = (result as AppResult.Failure).error
        // The screen has no "highlighted" field, so the useful message must surface.
        assertThat(error.message).isEqualTo("A valid plot identifier is required.")
        assertThat(error.message).doesNotContain("highlighted")
        assertThat(error.fieldErrors["plotId"]).isEqualTo("A valid plot identifier is required.")
    }

    @Test
    fun `a specific server message is kept even when field errors exist`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "error": {
                        "code": "VALIDATION_ERROR",
                        "message": "A plot boundary is required to compute NDVI.",
                        "details": [
                          { "field": "geoJsonBoundary", "message": "This plot has no saved boundary." }
                        ]
                      },
                      "requestId": "req-3"
                    }
                    """.trimIndent(),
                ),
        )

        val parser = NetworkErrorParser(json)
        val result = runCatchingApi(parser::parse) {
            api.latestNdvi("11111111-2222-3333-4444-555555555555")
        }

        val error = (result as AppResult.Failure).error
        // A specific, actionable server message wins over the per-field text.
        assertThat(error.message).isEqualTo("A plot boundary is required to compute NDVI.")
    }

    @Test
    fun `a 5xx never surfaces raw server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"code":"INTERNAL","message":"DB password leaked in stack trace"}}""",
                ),
        )

        val parser = NetworkErrorParser(json)
        val result = runCatchingApi(parser::parse) {
            api.login(LoginRequest(identifier = "x", password = "y"))
        }

        val error = (result as AppResult.Failure).error
        assertThat(error.code).isEqualTo("INTERNAL")
        assertThat(error.message).doesNotContain("stack trace")
        assertThat(error.message).isEqualTo("Something went wrong on our side. Please try again.")
    }

    private companion object {
        val AUTH_RESPONSE = """
            {
              "user": {
                "userId": "user-1",
                "fullName": "Ada Mokoena",
                "phoneNumber": "+26771234567",
                "email": null,
                "role": "FARM_OWNER",
                "accountStatus": "VERIFIED",
                "avatarUrl": null,
                "notificationPreferences": {
                  "orders": true, "sync": true, "satellite": false, "marketplace": true
                },
                "verifiedAt": "2026-01-01T00:00:00.000Z",
                "createdAt": "2025-12-01T00:00:00.000Z",
                "updatedAt": "2026-01-01T00:00:00.000Z"
              },
              "accessToken": "access-1",
              "refreshToken": "refresh-1",
              "expiresIn": 900
            }
        """.trimIndent()

        val VALIDATION_ERROR = """
            {
              "error": {
                "code": "VALIDATION_ERROR",
                "message": "Please correct the highlighted fields.",
                "details": [
                  { "field": "phoneNumber", "message": "Phone number is required." },
                  { "field": "password", "message": "Password must be at least 6 characters." }
                ]
              },
              "requestId": "req-1"
            }
        """.trimIndent()
    }
}
