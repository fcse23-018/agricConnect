package bw.agriconnect.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire types for the sync engine (docs/02-API-CONTRACT.md §2.10), the shared error
 * envelope used by [bw.agriconnect.data.remote.NetworkErrorParser], and the
 * unauthenticated health probe (§4).
 */

/**
 * `POST /sync/push`. `payload` is transported as a JSON *string* (the contract
 * shows `"payload":"{…}"`), which matches [bw.agriconnect.domain.model.SyncQueueItem.payload]
 * and avoids re-encoding a payload that is already serialised.
 */
@Serializable
data class SyncPushRequest(
    val items: List<SyncItemDto>,
    val deviceId: String? = null,
)

@Serializable
data class SyncItemDto(
    val queueId: String,
    val entityType: String,
    val entityId: String,
    /** `CREATE` | `UPDATE` | `DELETE`. */
    val operation: String,
    val payload: String,
    /** ISO-8601 timestamp of the device-side edit (drives Last-Write-Wins). */
    val clientTimestamp: String,
    val retryCount: Int = 0,
)

@Serializable
data class SyncPushResponse(
    val serverTime: String,
    val results: List<SyncResultDto> = emptyList(),
)

@Serializable
data class SyncResultDto(
    val queueId: String,
    /** `SYNCED` | `CONFLICT` | `FAILED`. */
    val status: String,
    val serverEntityId: String? = null,
    /** Present for `CONFLICT`: the authoritative server copy of the record. */
    val serverEntity: JsonElement? = null,
    val serverUpdatedAt: String? = null,
    /** Present for `FAILED`, e.g. `VALIDATION_ERROR`. */
    val reason: String? = null,
)

/**
 * `GET /sync/pull`. **The real server response groups deltas by collection**
 * (`backend/src/modules/sync/sync.service.js` → `pullChanges`), e.g.
 * `{ serverTime, changes: { plots: [...], harvests: [...], … } }`, where each
 * element is the same public row shape the REST list endpoints return.
 *
 * The [SyncChangesDto] collections are typed to the existing `*Dto` shapes (which
 * mirror `backend/src/utils/serialize.js` exactly) so a pulled row decodes straight
 * into the same model the repositories already use. Because the individual fields
 * default to `emptyList`, a role that receives only a subset (e.g. an employee gets
 * no `plots`) and a response that omits a collection both decode cleanly; an unknown
 * *future* collection is dropped by `ignoreUnknownKeys` rather than failing the pull.
 *
 * Note: pull rows carry no `deletedAt`, so a soft-delete cannot currently be
 * expressed by this endpoint (see the sync-layer report); deletions are conveyed
 * only through the push `CONFLICT`/ledger path.
 */
@Serializable
data class SyncPullResponse(
    val serverTime: String,
    val changes: SyncChangesDto = SyncChangesDto(),
)

@Serializable
data class SyncChangesDto(
    val plots: List<FarmPlotDto> = emptyList(),
    val harvests: List<HarvestDto> = emptyList(),
    val livestock: List<LivestockDto> = emptyList(),
    val sales: List<SaleDto> = emptyList(),
    val expenses: List<ExpenseDto> = emptyList(),
    val listings: List<ListingDto> = emptyList(),
    val orders: List<OrderDto> = emptyList(),
    val notifications: List<NotificationDto> = emptyList(),
    val ndvi: List<NdviDto> = emptyList(),
)

/**
 * The flat change shape sketched in docs/02-API-CONTRACT.md §2.10. Retained because
 * [bw.agriconnect.data.remote.dto.operationEnum] and any future endpoint that emits a
 * per-item change list use it; the current `/sync/pull` does not (see [SyncPullResponse]).
 */
@Serializable
data class SyncChangeDto(
    val entityType: String,
    val entityId: String,
    /** `CREATE` | `UPDATE` | `DELETE`; null when the delta is a full snapshot. */
    val operation: String? = null,
    val payload: JsonElement? = null,
    val serverUpdatedAt: String? = null,
)

/* ------------------------------ error envelope ---------------------------- */

/** `{ "error": {...}, "requestId": "..." }` from the backend error handler. */
@Serializable
data class ErrorEnvelope(
    val error: ErrorBody,
    val requestId: String? = null,
)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val details: List<FieldError>? = null,
)

@Serializable
data class FieldError(
    val field: String,
    val message: String,
)

/* ---------------------------------- health -------------------------------- */

/** `GET /health` (host root, unauthenticated): `{status, version, time}`. */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String? = null,
    val time: String? = null,
)
