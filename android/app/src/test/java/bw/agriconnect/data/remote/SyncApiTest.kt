package bw.agriconnect.data.remote

import bw.agriconnect.data.remote.dto.SyncItemDto
import bw.agriconnect.data.remote.dto.SyncPushRequest
import bw.agriconnect.data.remote.dto.statusEnum
import bw.agriconnect.domain.model.SyncStatus
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Contract test for `POST /sync/push`, with particular attention to the
 * `CONFLICT` result: it is the one path where the server returns the authoritative
 * copy of a record and the client must be able to read it.
 */
class SyncApiTest {

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
    fun `push posts items and parses synced, conflict and failed results`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(PUSH_RESPONSE),
        )

        val response = api.syncPush(
            idempotencyKey = "batch-7",
            body = SyncPushRequest(
                deviceId = "device-1",
                items = listOf(
                    SyncItemDto(
                        queueId = "q-1",
                        entityType = "SaleTransaction",
                        entityId = "local-1",
                        operation = "CREATE",
                        payload = """{"item":"Maize","amount":120.00}""",
                        clientTimestamp = "2026-02-01T08:00:00.000Z",
                        retryCount = 0,
                    ),
                ),
            ),
        )

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v1/sync/push")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.getHeader("Idempotency-Key")).isEqualTo("batch-7")

        val requestBody = recorded.body.readUtf8()
        assertThat(requestBody).contains("\"operation\":\"CREATE\"")
        assertThat(requestBody).contains("\"deviceId\":\"device-1\"")
        // `payload` is a JSON *string* on the wire, per the contract.
        assertThat(requestBody).contains("\"payload\":\"{")

        assertThat(response.serverTime).isEqualTo("2026-02-01T08:00:05.000Z")
        assertThat(response.results).hasSize(3)

        val conflict = response.results.first { it.queueId == "q-2" }
        assertThat(conflict.statusEnum()).isEqualTo(SyncStatus.CONFLICT)
        assertThat(conflict.serverUpdatedAt).isEqualTo("2026-01-31T22:00:00.000Z")
        assertThat(conflict.serverEntity).isNotNull()
        assertThat(conflict.serverEntity!!.jsonObject["item"]!!.jsonPrimitive.content)
            .isEqualTo("Maize")

        val failed = response.results.first { it.queueId == "q-3" }
        assertThat(failed.statusEnum()).isEqualTo(SyncStatus.FAILED)
        assertThat(failed.reason).isEqualTo("VALIDATION_ERROR")

        val synced = response.results.first { it.queueId == "q-1" }
        assertThat(synced.statusEnum()).isEqualTo(SyncStatus.SYNCED)
        assertThat(synced.serverEntityId).isEqualTo("server-1")
    }

    private companion object {
        val PUSH_RESPONSE = """
            {
              "serverTime": "2026-02-01T08:00:05.000Z",
              "results": [
                { "queueId": "q-1", "status": "SYNCED", "serverEntityId": "server-1",
                  "serverUpdatedAt": "2026-02-01T08:00:05.000Z" },
                { "queueId": "q-2", "status": "CONFLICT",
                  "serverEntity": { "item": "Maize", "amount": 150.00, "updatedAt": "2026-01-31T22:00:00.000Z" },
                  "serverUpdatedAt": "2026-01-31T22:00:00.000Z" },
                { "queueId": "q-3", "status": "FAILED", "reason": "VALIDATION_ERROR" }
              ]
            }
        """.trimIndent()
    }
}
