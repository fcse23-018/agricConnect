package bw.agriconnect.data.repository

import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.domain.model.SyncOperation
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
 * Upper bound on server pages walked by a `refreshX`. Refresh caches for offline use;
 * a runaway `hasMore` from the server must not spin forever. 50 × 100 rows is far
 * beyond any realistic v1 dataset.
 */
internal const val MAX_REFRESH_PAGES = 50

/** The single page size used by `refreshX`; the contract caps `pageSize` at 100. */
internal const val REFRESH_PAGE_SIZE = 100
