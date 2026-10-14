package bw.agriconnect.data.repository

import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.data.remote.dto.PageDto
import bw.agriconnect.domain.model.Expense
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.LivestockCount
import bw.agriconnect.domain.model.ProduceListing
import bw.agriconnect.domain.model.SaleTransaction
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Small helpers shared by the offline-first repositories.
 *
 * Why a shared file: every `saveX` needs the same two things — a synchronised
 * ISO-8601 timestamp for the entity's `updated_at`, and a durable outbox entry so a
 * crash between the local write and the network call can never lose work. Keeping the
 * construction in one place means every entity's timestamp and queue id are produced
 * the same way.
 */

/**
 * UTC ISO-8601 with milliseconds, matching exactly the format the API and
 * [bw.agriconnect.data.remote.dto.Mappers] use. A single formatter guarded by
 * `synchronized` because [SimpleDateFormat] is not thread-safe and repositories are
 * called from multiple dispatchers.
 */
private val isoMillis: SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

internal fun nowIso(): String = synchronized(isoMillis) { isoMillis.format(Date()) }

/**
 * A device-generated outbox entry. `queueId` doubles as the server-side idempotency
 * key for `/sync/push`, so re-sending after a transient failure is always safe.
 */
internal fun newSyncQueueItem(
    entityType: String,
    entityId: String,
    operation: SyncOperation,
    payload: String,
): SyncQueueEntity = SyncQueueEntity(
    queueId = UUID.randomUUID().toString(),
    entityType = entityType,
    entityId = entityId,
    operation = operation,
    payload = payload,
    createdAt = System.currentTimeMillis(),
)

/**
 * Removes any outstanding work for [entityId]. Used when a record the server never
 * acknowledged is deleted locally: pushing a DELETE for an id the server has never
 * seen would 404 forever, so the queued CREATE/UPDATE is dropped instead.
 */
internal suspend fun SyncQueueDao.dropPendingFor(entityId: String) {
    pendingItems()
        .filter { it.entityId == entityId }
        .forEach { delete(it.queueId) }
}

/**
 * Collapses repeated offline edits of one record down to the newest outbox entry.
 *
 * Called *after* the new entry has been written and enqueued (so it can name the entry
 * to keep). Doing the dedupe second means a crash in between leaves two idempotent
 * queue rows rather than no queue row at all — losing a queue row would strand a local
 * edit that never reaches the server.
 */
internal suspend fun SyncQueueDao.dropPendingExcept(entityId: String, keepQueueId: String) {
    pendingItems()
        .filter { it.entityId == entityId && it.queueId != keepQueueId }
        .forEach { delete(it.queueId) }
}

/**
 * True when the server may already hold this record, so deleting it locally must be
 * pushed as a DELETE rather than silently discarded.
 *
 * The tricky case is a row that has been uploaded but not yet re-keyed by a pull: the
 * push marks the queue item `SYNCED` (removed from [pendingItems]) while the local row
 * is still `PENDING`. Treating that as "never uploaded" and hard-deleting would let the
 * next pull resurrect the row. So the only combination we treat as "never uploaded" is
 * a non-[SyncStatus.SYNCED] row that still has a queued `CREATE` — the server cannot
 * have applied a create whose queue item has not yet been acknowledged.
 */
internal suspend fun SyncQueueDao.serverMayHold(
    entityType: String,
    entityId: String,
    syncStatus: SyncStatus,
): Boolean {
    if (syncStatus == SyncStatus.SYNCED) return true
    val queued = findByEntity(entityType, entityId)
    return queued == null || queued.operation != SyncOperation.CREATE
}

/**
 * Walks every page of a paginated list endpoint, oldest page first.
 *
 * Refresh exists to fill the offline cache, so it must not trust the server to stop
 * advertising more pages. [MAX_REFRESH_PAGES] caps the walk; the first empty page also
 * terminates, because a server that reports `hasMore` with no items would otherwise
 * loop forever.
 */
internal suspend fun <T> collectPages(fetch: suspend (page: Int) -> PageDto<T>): List<T> {
    val all = ArrayList<T>()
    var page = 1
    while (page <= MAX_REFRESH_PAGES) {
        val response = fetch(page)
        all += response.items
        if (!response.hasMore || response.items.isEmpty()) break
        page++
    }
    return all
}

/**
 * Server-schema-shaped `/sync/push` payloads.
 *
 * The payloads are built here (rather than from the REST `*Request` DTOs) because the
 * sync engine validates them with the *server's* zod schemas
 * (`backend/src/modules/sync/sync.entities.js`), which differ from the REST bodies in
 * two ways that matter:
 *  - a plot payload carries `centroidLat`/`centroidLng` (the REST create body does not);
 *  - a sale payload calls the date `saleDate` (the REST DTO calls it `date`).
 *
 * Null fields are omitted entirely: the backend coerces missing to `null`, and omitting
 * keeps the payload byte-stable across retries.
 */

internal fun Json.farmPlotPayload(plot: FarmPlot): String = encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        plot.clientId?.let { put("clientId", it) }
        put("plotName", plot.plotName)
        plot.areaHectares?.let { put("areaHectares", it) }
        // The boundary is persisted as an opaque JSON string; the server accepts either
        // a string or an object, but a real object is what the jsonb column stores.
        plot.geoJsonBoundary?.let { raw ->
            runCatching { parseToJsonElement(raw) }.getOrNull()?.let { put("geoJsonBoundary", it) }
        }
        plot.centroidLat?.let { put("centroidLat", it) }
        plot.centroidLng?.let { put("centroidLng", it) }
        plot.notes?.let { put("notes", it) }
    },
)

internal fun Json.harvestPayload(record: HarvestRecord): String = encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        record.clientId?.let { put("clientId", it) }
        record.plotId?.let { put("plotId", it) }
        put("cropType", record.cropType)
        put("quantityKg", record.quantityKg)
        put("harvestDate", record.harvestDate)
        record.notes?.let { put("notes", it) }
    },
)

internal fun Json.livestockPayload(count: LivestockCount): String = encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        count.clientId?.let { put("clientId", it) }
        put("animalType", count.animalType)
        put("headCount", count.headCount)
        put("recordDate", count.recordDate)
        count.notes?.let { put("notes", it) }
    },
)

internal fun Json.salePayload(sale: SaleTransaction): String = encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        sale.clientId?.let { put("clientId", it) }
        put("item", sale.item)
        put("quantity", sale.quantity)
        sale.unitPrice?.let { put("unitPrice", it) }
        put("amount", sale.amount)
        put("currency", sale.currency)
        sale.buyerName?.let { put("buyerName", it) }
        // The server schema names this `saleDate`; the domain field is `date`.
        put("saleDate", sale.date)
        sale.notes?.let { put("notes", it) }
    },
)

internal fun Json.expensePayload(expense: Expense): String = encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        expense.clientId?.let { put("clientId", it) }
        put("category", expense.category)
        expense.description?.let { put("description", it) }
        put("amount", expense.amount)
        put("currency", expense.currency)
        put("expenseDate", expense.expenseDate)
    },
)

/**
 * ProduceListing is keyed by its server uuid, so the payload deliberately carries no
 * `clientId`. `status` is also omitted: the sync endpoint never accepts it, and a new
 * server row always defaults to `DRAFT` (publish is a separate REST transition).
 */
internal fun Json.produceListingPayload(listing: ProduceListing): String = encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("produce", listing.produce)
        listing.description?.let { put("description", it) }
        put("quantity", listing.quantity)
        put("unit", listing.unit.name.lowercase(Locale.US))
        put("price", listing.price)
        listing.imageUrl?.let { put("imageUrl", it) }
        listing.locationText?.let { put("locationText", it) }
    },
)

/**
 * Upper bound on server pages walked by a `refreshX`. Refresh caches for offline use;
 * a runaway `hasMore` from the server must not spin forever. 50 × 100 rows is far
 * beyond any realistic v1 dataset.
 */
internal const val MAX_REFRESH_PAGES = 50

/** The single page size used by `refreshX`; the contract caps `pageSize` at 100. */
internal const val REFRESH_PAGE_SIZE = 100
