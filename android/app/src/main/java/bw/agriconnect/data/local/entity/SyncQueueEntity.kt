package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncQueueItem
import bw.agriconnect.domain.model.SyncStatus

/**
 * Durable outbox of local writes waiting to reach `/sync/push` (docs/02-API-CONTRACT.md §2.10).
 *
 * [queueId] is the device-generated id the server uses for idempotency, so re-sending an
 * item is always safe. [payload] is the serialised entity JSON exactly as the server
 * expects it; the queue stays transport-agnostic by keeping it a string.
 *
 * [createdAt] and [attemptedAt] are epoch milliseconds (not ISO strings) because the queue
 * is ordered and backoff-scheduled by time and never rendered — monotonic-ish Longs keep
 * ordering stable and avoid timezone parsing. [attemptedAt] is the last time the worker
 * touched the item, which the WorkManager backoff uses to avoid hammering the server.
 *
 * [entityType] is the domain-class simple name the server expects (e.g. `"SaleTransaction"`,
 * per docs/02-API-CONTRACT.md §2.10). Callers should use [EntityTypes] constants rather than
 * string literals so the queue and the push payload can never disagree.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["status", "created_at"]),
        Index(value = ["entity_type", "entity_id"]),
    ],
)
data class SyncQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "queue_id")
    val queueId: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "operation")
    val operation: SyncOperation,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "status")
    val status: SyncStatus = SyncStatus.PENDING,
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "attempted_at")
    val attemptedAt: Long? = null,
) {
    fun toDomain(): SyncQueueItem = SyncQueueItem(
        queueId = queueId,
        entityType = entityType,
        entityId = entityId,
        operation = operation,
        payload = payload,
        createdAt = createdAt,
        status = status,
        retryCount = retryCount,
        lastError = lastError,
    )

    companion object {
        fun fromDomain(domain: SyncQueueItem, attemptedAt: Long? = null): SyncQueueEntity =
            SyncQueueEntity(
                queueId = domain.queueId,
                entityType = domain.entityType,
                entityId = domain.entityId,
                operation = domain.operation,
                payload = domain.payload,
                createdAt = domain.createdAt,
                status = domain.status,
                retryCount = domain.retryCount,
                lastError = domain.lastError,
                attemptedAt = attemptedAt,
            )
    }

    /** Canonical `entityType` values for the sync queue (domain-class simple names). */
    object EntityTypes {
        const val FARM_PLOT = "FarmPlot"
        const val HARVEST_RECORD = "HarvestRecord"
        const val LIVESTOCK_COUNT = "LivestockCount"
        const val SALE_TRANSACTION = "SaleTransaction"
        const val EXPENSE = "Expense"
        const val PRODUCE_LISTING = "ProduceListing"
    }
}
