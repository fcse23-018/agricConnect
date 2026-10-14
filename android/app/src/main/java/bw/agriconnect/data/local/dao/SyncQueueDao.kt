package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import bw.agriconnect.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * The local outbox for `/sync/push`.
 *
 * `PENDING` items are new work; `CONFLICT` items need the user to resolve a server-newer
 * copy before they can be re-pushed (docs/02-API-CONTRACT.md §2.10). [pendingItems] returns
 * both, oldest-first, so a single worker pass drains the queue in the order the user
 * created the work.
 *
 * `FAILED` items are intentionally excluded from [pendingItems]: the server rejected them
 * (validation/authorisation), so automatic retries would loop forever. They remain in the
 * table for the UI to surface and for [deleteSynced] housekeeping.
 *
 * `attempted_at` is stamped with SQLite's own clock (`strftime`) inside the UPDATE so the
 * "last attempted" timestamp stays consistent with any other writers without threading a
 * clock through the DAO.
 */
@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity)

    /** Newest work first is wrong for a queue; this is oldest-first by design. */
    @Query(
        """
        SELECT * FROM sync_queue
        WHERE status IN ('PENDING', 'CONFLICT')
        ORDER BY created_at ASC
        """,
    )
    suspend fun pendingItems(): List<SyncQueueEntity>

    /** Reactive badge count for the "N items waiting to sync" indicator. */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'CONFLICT')")
    fun pendingCount(): Flow<Int>

    /** One-shot count for the worker / tests that do not need to observe. */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status IN ('PENDING', 'CONFLICT')")
    suspend fun pendingCountNow(): Int

    /** Total rows (any status); used by tests and diagnostics. */
    @Query("SELECT COUNT(*) FROM sync_queue")
    suspend fun countAll(): Int

    @Query("SELECT * FROM sync_queue WHERE queue_id = :queueId LIMIT 1")
    suspend fun findById(queueId: String): SyncQueueEntity?

    /**
     * The oldest outstanding write for one entity, keyed by the `(entity_type, entity_id)`
     * index. Pull-time conflict resolution uses this to avoid clobbering a local edit that
     * has not reached the server yet. Only `PENDING`/`CONFLICT` rows count: a `FAILED` row
     * is parked for the UI (never retried), so it must not block an authoritative pull.
     */
    @Query(
        """
        SELECT * FROM sync_queue
        WHERE entity_type = :entityType AND entity_id = :entityId
          AND status IN ('PENDING', 'CONFLICT')
        ORDER BY created_at ASC
        LIMIT 1
        """,
    )
    suspend fun findByEntity(entityType: String, entityId: String): SyncQueueEntity?

    @Query(
        """
        UPDATE sync_queue
        SET status = 'SYNCED',
            last_error = NULL,
            attempted_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
        WHERE queue_id = :queueId
        """,
    )
    suspend fun markSynced(queueId: String)

    @Query(
        """
        UPDATE sync_queue
        SET status = 'CONFLICT',
            last_error = :error,
            attempted_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
        WHERE queue_id = :queueId
        """,
    )
    suspend fun markConflict(queueId: String, error: String?)

    @Query(
        """
        UPDATE sync_queue
        SET status = 'FAILED',
            retry_count = retry_count + 1,
            last_error = :error,
            attempted_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
        WHERE queue_id = :queueId
        """,
    )
    suspend fun markFailed(queueId: String, error: String?)

    /**
     * Transient failure: bump the retry counter and keep the item `PENDING` so it is
     * picked up again after the WorkManager backoff (10s→20s→…≤1h).
     */
    @Query(
        """
        UPDATE sync_queue
        SET status = 'PENDING',
            retry_count = retry_count + 1,
            last_error = :error,
            attempted_at = CAST(strftime('%s', 'now') AS INTEGER) * 1000
        WHERE queue_id = :queueId
        """,
    )
    suspend fun bumpRetry(queueId: String, error: String?)

    @Query("DELETE FROM sync_queue WHERE queue_id = :queueId")
    suspend fun delete(queueId: String)

    /** Housekeeping: drop acknowledged items after a successful push pass. */
    @Query("DELETE FROM sync_queue WHERE status = 'SYNCED'")
    suspend fun deleteSynced()

    /** Used on logout-after-purge to drop a whole account's queued work. */
    @Query("DELETE FROM sync_queue")
    suspend fun deleteAll()
}
