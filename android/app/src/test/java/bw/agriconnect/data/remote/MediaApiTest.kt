package bw.agriconnect.data.remote

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.data.repository.MediaRepository
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Contract test for `POST /uploads` — the single endpoint every image in the app
 * (listing, plot) goes through.
 *
 * The multipart field name is the fragile part of this contract: the server only
 * accepts the file under `image` (see `backend/.../uploads.routes.js`), so a rename
 * here would break uploads silently with a 400. These cases pin the path, the method,
 * the field name and the `{ url }` response parse.
 */
class MediaApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ApiService
    private lateinit var repository: MediaRepository

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
        repository = MediaRepository(api, NetworkErrorParser(json))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `upload posts a multipart image and returns the relative path`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"url":"/uploads/9f8e7d6c.jpg"}"""),
        )

        val part = MultipartBody.Part.createFormData(
            "image",
            "upload.jpg",
            byteArrayOf(1, 2, 3).toRequestBody("image/jpeg".toMediaType()),
        )

        val response = api.uploadImage(part)

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/uploads")
        assertThat(recorded.method).isEqualTo("POST")
        val body = recorded.body.readUtf8()
        assertThat(body).contains("name=\"image\"")
        assertThat(body).contains("filename=\"upload.jpg\"")
        assertThat(body).contains("Content-Type: image/jpeg")

        // The server persists an origin-relative path, not an absolute URL.
        assertThat(response.url).isEqualTo("/uploads/9f8e7d6c.jpg")
    }

    @Test
    fun `repository returns the stored path on success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"url":"/uploads/photo.webp"}"""),
        )

        val result = repository.uploadImage(byteArrayOf(9, 9), "image/webp", "photo.webp")

        when (result) {
            is AppResult.Success -> assertThat(result.data).isEqualTo("/uploads/photo.webp")
            is AppResult.Failure -> error("expected success, got ${result.error}")
        }
    }

    @Test
    fun `a server failure never surfaces raw server text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"code":"INTERNAL","message":"secret disk path /srv/uploads"}}""",
                ),
        )

        val result = repository.uploadImage(byteArrayOf(1), "image/jpeg", "big.jpg")

        when (result) {
            is AppResult.Success -> error("expected failure, got ${result.data}")
            is AppResult.Failure -> {
                assertThat(result.error.code).isEqualTo(AppError.INTERNAL)
                assertThat(result.error.message).doesNotContain("/srv/uploads")
                assertThat(result.error.message)
                    .isEqualTo("Something went wrong on our side. Please try again.")
            }
        }
    }
}
