package bw.agriconnect.data.sync

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.dto.SyncPullResponse
import bw.agriconnect.data.remote.dto.SyncPushRequest
import bw.agriconnect.data.remote.dto.toSyncItemDto
import bw.agriconnect.domain.model.SyncQueueItem
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tally for one [SyncEngine.push] pass. `transientFailures` are items left
 * `PENDING` for a later attempt (network/5xx/rate-limit); the other three buckets are
 * terminal for this pass.
 */
data class SyncOutcome(
    val attempted: Int = 0,
    val succeeded: Int = 0,
    val conflicted: Int = 0,
    val failed: Int = 0,
    val transientFailures: Int = 0,
) {
    val hasTransientFailures: Boolean get() = transientFailures > 0

    companion object {
        val EMPTY = SyncOutcome()
    }
}

/**
 * The combined result of [SyncEngine.syncNow]: the push tally plus the pull outcome.
 *
 * [hasOnlyTransientFailures] is the worker's retry predicate. It is true only when
 * *something* failed transiently and *nothing* failed permanently — a permanently
 * rejected item will never succeed on a retry, so re-running the whole pass for its
 * sake would just burn battery.
 */
data class SyncRunResult(
    val push: SyncOutcome,
    val pull: AppResult<SyncPullResponse>,
) {
    /** Push transient failures, plus one for a network-only pull failure. */
    val transientFailures: Int
        get() = push.transientFailures +
            if (pull is AppResult.Failure && pull.error.isNetwork) 1 else 0

    val permanentFailures: Int get() = push.failed

    val hasOnlyTransientFailures: Boolean
        get() = transientFailures > 0 && permanentFailures == 0
}

/**
 * The offline-first sync engine: drains the local outbox to `/sync/push`, then pulls
 * server deltas and hands them to an applier.
 *
 * Design rules:
 *  - **The queue is the source of truth for outbound work.** A failed item is never
 *    dropped; it stays `PENDING` (transient) or `FAILED` (permanent, parked).
 *  - **Expected failures never throw.** Network/HTTP failures are classified and
 *    returned in [SyncOutcome]/[AppResult], so [SyncWorker] can decide whether to
 *    retry. Only coroutine cancellation propagates.
 *  - **This class never touches a DAO for entity data.** Pull only calls the
 *    supplied [applier]; *all* Room writes for pulled rows live in [SyncApplier].
 */
@Singleton
class SyncEngine @Inject constructor(
    private val queue: SyncQueue,
    private val api: ApiService,
) {

    /**
     * The `serverTime` of the last successful push, used as the `since` cursor for the
     * following pull so we do not re-download changes we just pushed ourselves. It is
     * in-memory only: after a process restart the first pull is a full snapshot, which
     * is correct (upserts are idempotent) if a little larger.
     */
    @Volatile
    private var lastServerTime: String? = null

    /**
     * Push every outstanding queue item, oldest-first, in fixed batches.
     *
     * **Why batches of [BATCH_SIZE].** The endpoint accepts up to 500 items, but the
     * whole request is rebuilt into one JSON body and one DB round-trip per item on the
     * server. 50 keeps the body small enough for a flaky mobile link (a dropped
     * connection costs at most 50 items' worth of resend, all idempotent by `queueId`)
     * while draining a realistic offline backlog in a couple of requests. It also stays
     * well under the endpoint's 120 req / 15 min user rate limit even for a burst.
     *
     * Idempotency is per item (`queueId`); the optional batch `Idempotency-Key` is a
     * stable hash of the batch's queue ids, so replaying the identical batch is a no-op
     * even before the per-item ledger check.
     */
    suspend fun push(): SyncOutcome {
        val pending = queue.pending()
        if (pending.isEmpty()) {
            queue.deleteSynced()
            return SyncOutcome.EMPTY
        }

        var attempted = 0
        var succeeded = 0
        var conflicted = 0
        var failed = 0
        var transientFailures = 0

        for (batch in pending.chunked(BATCH_SIZE)) {
            attempted += batch.size

            val response = try {
                api.syncPush(
                    idempotencyKey = idempotencyKey(batch),
                    body = SyncPushRequest(items = batch.map { it.toSyncItemDto() }),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The whole batch failed before any per-item result came back. Classify
                // once, then apply the same disposition to every item in the batch.
                val reason = failureReason(e)
                if (isTransient(e)) {
                    batch.forEach { queue.bumpRetry(it.queueId, reason) }
                    transientFailures += batch.size
                } else {
                    batch.forEach { queue.markFailed(it.queueId, reason) }
                    failed += batch.size
                }
                continue
            }

            lastServerTime = response.serverTime
            val byQueueId = response.results.associateBy { it.queueId }
            for (item in batch) {
                val result = byQueueId[item.queueId]
                when {
                    // The server omitted a result: treat as transient. The item is
                    // idempotent by queueId, so re-sending it cannot duplicate work.
                    result == null -> {
                        queue.bumpRetry(item.queueId, MISSING_RESULT)
                        transientFailures++
                    }

                    result.status.equals(STATUS_SYNCED, ignoreCase = true) -> {
                        queue.markSynced(item.queueId)
                        succeeded++
                    }

                    result.status.equals(STATUS_CONFLICT, ignoreCase = true) -> {
                        queue.markConflict(item.queueId, result.reason)
                        conflicted++
                    }

                    // A FAILED item is permanent by default (the server rejected the
                    // payload/role/owner). The only exceptions are the documented
                    // transient server conditions, which we retry via backoff.
                    result.status.equals(STATUS_FAILED, ignoreCase = true) &&
                        isTransientReason(result.reason) -> {
                        queue.bumpRetry(item.queueId, result.reason)
                        transientFailures++
                    }

                    result.status.equals(STATUS_FAILED, ignoreCase = true) -> {
                        queue.markFailed(item.queueId, result.reason)
                        failed++
                    }

                    // Unknown status: retry rather than park, since the item is safe to
                    // re-send and a transient server response would otherwise be lost.
                    else -> {
                        queue.bumpRetry(item.queueId, "$UNKNOWN_STATUS_PREFIX${result.status}")
                        transientFailures++
                    }
                }
            }
        }

        // Housekeeping: acknowledged rows are dropped once the pass is done.
        queue.deleteSynced()

        return SyncOutcome(
            attempted = attempted,
            succeeded = succeeded,
            conflicted = conflicted,
            failed = failed,
            transientFailures = transientFailures,
        )
    }

    /**
     * Pull server-authoritative deltas and hand them to [applier]. This method never
     * writes to Room itself: the applier owns every write so the engine stays a pure
     * transport/orchestration layer and can be tested with a recording lambda.
     *
     * @param since ISO-8601 cursor; `null` asks for the full snapshot.
     */
    suspend fun pull(
        since: String?,
        applier: suspend (SyncPullResponse) -> Unit,
    ): AppResult<SyncPullResponse> = runCatchingApi {
        val response = api.syncPull(since)
        applier(response)
        response
    }

    /** One full pass: drain the outbox first, then reconcile downward. */
    suspend fun syncNow(applier: suspend (SyncPullResponse) -> Unit): SyncRunResult {
        val push = push()
        val pull = pull(since = lastServerTime, applier = applier)
        return SyncRunResult(push = push, pull = pull)
    }

    /**
     * A stable, deterministic key for a batch: the same set of queue ids in the same
     * order always hashes the same, so a WorkManager replay of an identical batch is
     * deduplicated by the server even before its per-item `queueId` ledger.
     */
    private fun idempotencyKey(batch: List<SyncQueueItem>): String =
        "sync-" + batch.joinToString(",") { it.queueId }.hashCode().toUInt().toString(16)

    /** True when a whole-batch transport failure is worth retrying with backoff. */
    private fun isTransient(t: Throwable): Boolean = when (t) {
        is HttpException -> t.code() == 401 || t.code() == 408 || t.code() == 425 ||
            t.code() == 429 || t.code() >= 500
        // Connectivity/timeouts: the canonical retry case.
        is IOException -> true
        // Anything unrecognised is retried rather than parked: re-sending is
        // idempotent by queueId, and parking a user's data on a client bug is worse
        // than one more attempt.
        else -> true
    }

    /** True when a server-reported `FAILED` reason is a transient condition. */
    private fun isTransientReason(reason: String?): Boolean {
        if (reason == null) return false
        return reason.uppercase(Locale.US) in TRANSIENT_REASONS
    }

    /** A short, non-leaking reason string for the queue's `last_error` column. */
    private fun failureReason(t: Throwable): String = when (t) {
        is HttpException -> "HTTP_${t.code()}"
        is IOException -> "NETWORK"
        else -> t::class.simpleName ?: "UNKNOWN"
    }

    companion object {
        /**
         * Items per push request. Chosen to bound request body size and the blast
         * radius of a dropped connection, while staying far below the endpoint's
         * 500-item cap and 120 req / 15 min rate limit. See [push].
         */
        const val BATCH_SIZE = 50

        private const val STATUS_SYNCED = "SYNCED"
        private const val STATUS_CONFLICT = "CONFLICT"
        private const val STATUS_FAILED = "FAILED"
        private const val MISSING_RESULT = "MISSING_RESULT"
        private const val UNKNOWN_STATUS_PREFIX = "UNKNOWN_STATUS:"

        /**
         * Server `FAILED` reasons that are retryable. Everything else (validation,
         * auth, ownership, unknown entity) is permanent and parks the item.
         * Mirrors `REASON` in backend/src/modules/sync/sync.service.js.
         */
        private val TRANSIENT_REASONS = setOf(
            "INTERNAL",
            "RATE_LIMITED",
            "TIMEOUT",
            "SERVICE_UNAVAILABLE",
            "NETWORK",
            "SERVER_ERROR",
        )
    }
}
