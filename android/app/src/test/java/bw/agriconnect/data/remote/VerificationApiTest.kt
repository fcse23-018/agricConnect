package bw.agriconnect.data.remote

import bw.agriconnect.data.remote.dto.IdentifierRequest
import bw.agriconnect.data.remote.dto.VerifyCodeRequest
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.AccountStatus
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
 * Wire contract for account verification.
 *
 * The app's honesty rules depend on these fields decoding exactly:
 *  - `delivery.delivered` + `delivery.reason` are what let it say "no SMS was
 *    sent" instead of "a code has been sent";
 *  - an absent `delivery` block means "not reported" and must stay null;
 *  - `confirm` returns the verified user so the badge updates without a second
 *    request that could fail.
 */
class VerificationApiTest {

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
    fun `request posts the identifier and decodes a trial rejection honestly`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "message": "If an account matches those details, a verification code has been sent.",
                      "expiresInMinutes": 10,
                      "delivery": {
                        "channel": "SMS",
                        "delivered": false,
                        "reason": "TRIAL_UNVERIFIED_NUMBER"
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val response = api.verificationRequest(IdentifierRequest("+26774887802"))

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/auth/verification/request")
        assertThat(recorded.body.readUtf8()).contains("\"identifier\":\"+26774887802\"")

        assertThat(response.expiresInMinutes).isEqualTo(10)
        assertThat(response.delivery?.channel).isEqualTo("SMS")
        assertThat(response.delivery?.delivered).isFalse()
        assertThat(response.delivery?.reason).isEqualTo("TRIAL_UNVERIFIED_NUMBER")
    }

    @Test
    fun `a delivery block that is absent stays unknown, not delivered`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"If an account matches those details, a verification code has been sent."}"""),
        )

        val response = api.verificationRequest(IdentifierRequest("+26774887802"))

        assertThat(response.delivery).isNull()
        assertThat(response.expiresInMinutes).isNull()
        assertThat(response.devCode).isNull()
    }

    @Test
    fun `a delivered SMS decodes as delivered with no reason`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"message":"Sent.","delivery":{"channel":"SMS","delivered":true}}""",
                ),
        )

        val response = api.verificationRequest(IdentifierRequest("+26774887802"))

        assertThat(response.delivery?.delivered).isTrue()
        assertThat(response.delivery?.reason).isNull()
    }

    @Test
    fun `confirm returns the verified user`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "message": "Your account has been verified.",
                      "user": {
                        "userId": "u1",
                        "fullName": "Neo Mokoena",
                        "phoneNumber": "+26774887802",
                        "email": null,
                        "role": "FARM_OWNER",
                        "accountStatus": "VERIFIED",
                        "verifiedAt": "2026-10-27T08:00:00.000Z",
                        "createdAt": "2026-10-01T08:00:00.000Z",
                        "updatedAt": "2026-10-27T08:00:00.000Z"
                      }
                    }
                    """.trimIndent(),
                ),
        )

        val response = api.verificationConfirm(VerifyCodeRequest("+26774887802", "123456"))

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/auth/verification/confirm")
        assertThat(recorded.body.readUtf8()).contains("\"code\":\"123456\"")

        val user = response.user?.toDomain()
        assertThat(user).isNotNull()
        assertThat(user!!.accountStatus).isEqualTo(AccountStatus.VERIFIED)
        assertThat(user.verifiedAt).isEqualTo("2026-10-27T08:00:00.000Z")
    }

    @Test
    fun `an older server that omits the user still decodes`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"Your account has been verified."}"""),
        )

        val response = api.verificationConfirm(VerifyCodeRequest("+26774887802", "123456"))

        assertThat(response.message).isEqualTo("Your account has been verified.")
        assertThat(response.user).isNull()
    }
}
