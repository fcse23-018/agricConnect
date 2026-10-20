package bw.agriconnect.data.update

import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.dto.VersionManifestDto
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
 * Contract tests for the public `GET /app/version` wire shape (backend
 * `src/modules/app/app.routes.js`). They pin the path, the three response
 * bodies the server actually sends, and the tolerant parsing guarantees
 * (unknown keys ignored, missing fields defaulted) that keep a partial or
 * older server from ever crashing the client. Style mirrors `AuthApiTest`.
 */
class UpdateVersionApiTest {

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
    fun `available manifest parses every published field`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(AVAILABLE_BODY),
        )

        val manifest = api.getAppVersion()

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/app/version")
        assertThat(recorded.method).isEqualTo("GET")
        // The endpoint is public: no Authorization header must be attached.
        assertThat(recorded.getHeader("Authorization")).isNull()

        assertThat(manifest.available).isTrue()
        assertThat(manifest.published).isTrue()
        assertThat(manifest.latestVersionName).isEqualTo("1.5.0")
        assertThat(manifest.latestVersionCode).isEqualTo(15)
        assertThat(manifest.minSupportedVersionCode).isEqualTo(7)
        assertThat(manifest.downloadUrl).isEqualTo("https://cdn.example.com/agriconnect-1.5.0.apk")
        assertThat(manifest.packageName).isEqualTo("bw.agriconnect")
        assertThat(manifest.releaseNotes).containsExactly("Fix irrigation scheduling.", "Faster NDVI tiles.")
        assertThat(manifest.releasedAt).isEqualTo("2026-10-01")
        assertThat(manifest.requiredMessage).isEqualTo("This version is no longer supported.")
        assertThat(manifest.optionalMessage).isEqualTo("A newer version is available.")
        assertThat(manifest.checksumSha256)
            .isEqualTo(SHA256_HEX)
        assertThat(manifest.sizeBytes).isEqualTo(12_345_678L)
        assertThat(manifest.releaseNotesUrl).isEqualTo("https://example.com/releases/1.5.0")

        // And the decision pipeline consumes it directly.
        val decision = decide(currentVersionCode = 3, manifest = manifest)
        assertThat(decision).isInstanceOf(UpdateDecision.Required::class.java)
    }

    @Test
    fun `available false with reason parses and decides to None`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(DISABLED_BODY),
        )

        val manifest = api.getAppVersion()

        assertThat(manifest.available).isFalse()
        assertThat(manifest.reason).isEqualTo("updates are disabled")
        assertThat(manifest.latestVersionCode).isEqualTo(0)
        assertThat(manifest.downloadUrl).isNull()

        val decision = decide(currentVersionCode = 1, manifest = manifest)
        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
        assertThat(decision.reason).isEqualTo("updates are disabled")
    }

    @Test
    fun `partial body from an old server hits safe defaults instead of failing`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"available": true}"""),
        )

        val manifest = api.getAppVersion()

        // Missing version code and URL default to "nothing usable to offer".
        assertThat(manifest.available).isTrue()
        assertThat(manifest.latestVersionCode).isEqualTo(0)
        assertThat(manifest.minSupportedVersionCode).isNull()
        assertThat(manifest.releaseNotes).isEmpty()

        val decision = decide(currentVersionCode = 1, manifest = manifest)
        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `unknown keys from a future server are ignored`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "available": true,
                      "latestVersionCode": 20,
                      "minSupportedVersionCode": 12,
                      "downloadUrl": "https://cdn.example.com/v2.apk",
                      "rolloutPercentage": 50,
                      "phasedRollout": {"bucket": 1},
                      "futureField": {"nested": true}
                    }
                    """.trimIndent(),
                ),
        )

        val manifest = api.getAppVersion()

        assertThat(manifest.latestVersionCode).isEqualTo(20)
        assertThat(manifest.minSupportedVersionCode).isEqualTo(12)
        assertThat(manifest.downloadUrl).isEqualTo("https://cdn.example.com/v2.apk")

        val decision = decide(currentVersionCode = 10, manifest = manifest)
        assertThat(decision).isInstanceOf(UpdateDecision.Required::class.java)
    }

    @Test
    fun `available true with a non-positive latestVersionCode never prompts`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "available": true,
                      "latestVersionCode": 0,
                      "minSupportedVersionCode": 1,
                      "downloadUrl": "https://cdn.example.com/x.apk"
                    }
                    """.trimIndent(),
                ),
        )

        val manifest = api.getAppVersion()

        val decision = decide(currentVersionCode = 1, manifest = manifest)
        assertThat(decision).isInstanceOf(UpdateDecision.None::class.java)
    }

    @Test
    fun `a JSON null in place of a typed field coerces instead of crashing`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "available": true,
                      "latestVersionCode": null,
                      "releaseNotes": null
                    }
                    """.trimIndent(),
                ),
        )

        val manifest: VersionManifestDto = api.getAppVersion()

        assertThat(manifest.latestVersionCode).isEqualTo(0)
        assertThat(manifest.releaseNotes).isEmpty()
        assertThat(decide(1, manifest)).isInstanceOf(UpdateDecision.None::class.java)
    }

    private companion object {
        val SHA256_HEX = "a".repeat(64)

        val AVAILABLE_BODY = """
            {
              "available": true,
              "published": true,
              "latestVersionName": "1.5.0",
              "latestVersionCode": 15,
              "minSupportedVersionCode": 7,
              "downloadUrl": "https://cdn.example.com/agriconnect-1.5.0.apk",
              "packageName": "bw.agriconnect",
              "releaseNotes": [
                "Fix irrigation scheduling.",
                "Faster NDVI tiles."
              ],
              "releasedAt": "2026-10-01",
              "requiredMessage": "This version is no longer supported.",
              "optionalMessage": "A newer version is available.",
              "checksumSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "sizeBytes": 12345678,
              "releaseNotesUrl": "https://example.com/releases/1.5.0"
            }
        """.trimIndent()

        val DISABLED_BODY = """
            {
              "available": false,
              "reason": "updates are disabled"
            }
        """.trimIndent()
    }
}
