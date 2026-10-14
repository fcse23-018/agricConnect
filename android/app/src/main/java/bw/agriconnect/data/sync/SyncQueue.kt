package bw.agriconnect.data.sync

import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.domain.model.SyncQueueItem
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable outbox in front of `/sync/push`.
 *
 * This is the seam the [SyncEngine] drains and the UI counts against. Keeping it an
 * interface (rather than exposing [SyncQueueDao] directly) means the engine can be
 * unit-tested against a fake queue without a Room database, and the persistence
 * details (`strftime` timestamps, enum converters) stay in the data layer.
 *
 * Every mutator except [deleteSynced]/[deleteAll] addresses one row by its device
 * `queueId`, which is also the server's per-item idempotency key (§2.10) — so a
 * status transition is safe to replay.
 */
interface SyncQueue {

    /** Outstanding work, oldest first: both `PENDING` and `CONFLICT` rows. */
    suspend fun pending(): List<SyncQueueItem>

    /** Server acknowledged the item. */
    suspend fun markSynced(queueId: String)

    /** Server had a newer copy; the item now needs user-visible resolution. */
    suspend fun markConflict(queueId: String, reason: String?)

    /**
     * The server permanently rejected the item (validation/authorisation). It is
     * parked for the UI and is deliberately **not** retried, so a bad payload cannot
     * spin the worker forever.
     */
    suspend fun markFailed(queueId: String, reason: String?)

    /**
     * A *transient* failure (network, 5xx, rate limit): bump the retry counter and
     * leave the item `PENDING` so the next pass — after WorkManager's backoff — picks
     * it up again.
     */
    suspend fun bumpRetry(queueId: String, reason: String?)

    /** Housekeeping after a successful push: drop `SYNCED` rows. */
    suspend fun deleteSynced()

    /** Purge the whole outbox (logout-after-purge / account switch). */
    suspend fun deleteAll()

    /** Reactive count for the "N changes waiting to sync" indicator. */
    fun pendingCount(): Flow<Int>
}

/**
 * Room-backed [SyncQueue]. The mapping is the entity's own [SyncQueueEntity.toDomain],
 * so the queue and the engine cannot drift in field names.
 */
@Singleton
class RoomSyncQueue @Inject constructor(
    private val dao: SyncQueueDao,
) : SyncQueue {

    override suspend fun pending(): List<SyncQueueItem> =
        dao.pendingItems().map(SyncQueueEntity::toDomain)

    override suspend fun markSynced(queueId: String) {
        dao.markSynced(queueId)
    }

    override suspend fun markConflict(queueId: String, reason: String?) {
        dao.markConflict(queueId, reason)
    }

    override suspend fun markFailed(queueId: String, reason: String?) {
        dao.markFailed(queueId, reason)
    }

    override suspend fun bumpRetry(queueId: String, reason: String?) {
        dao.bumpRetry(queueId, reason)
    }

    override suspend fun deleteSynced() {
        dao.deleteSynced()
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    override fun pendingCount(): Flow<Int> = dao.pendingCount()
}
