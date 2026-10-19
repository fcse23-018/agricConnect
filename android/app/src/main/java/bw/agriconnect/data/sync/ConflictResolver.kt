package bw.agriconnect.data.sync

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How a local/server divergence should be settled. */
enum class Resolution {
    /** The device copy is the winner; the server delta is ignored. */
    USE_LOCAL,

    /** The server copy is the winner; it is written to Room as `SYNCED`. */
    USE_SERVER,

    /**
     * The two copies should be combined field-by-field. The LWW strategy never
     * produces this — it exists so a future three-way/field-level merger can be
     * dropped in without changing call sites or the [Resolution] type.
     */
    MERGE,
}

/**
 * Deterministic conflict resolution by edit timestamp (Architecture v1.1 §1 /
 * §9.5; docs/02-API-CONTRACT.md §2.10).
 *
 * **Why Last-Write-Wins is acceptable here.** AgriConnect's syncable records are
 * single-writer in practice: a plot, harvest, livestock count, sale or expense has
 * exactly one owner (a farm owner, or that owner plus their explicitly linked
 * farmers acting on the same farm), and the server independently enforces an
 * owned-entity check, so two *different* accounts can never race for the same row.
 * Every device edit is stamped with a monotonic-ish `createdAt`, which the queue
 * already sends to the server as `clientTimestamp`; the server applies the identical
 * rule (`server_updated_at > client_timestamp ⇒ CONFLICT`). Reusing the same ordering
 * on the client means the two sides always agree and a conflict is a genuine
 * "same record edited twice" edge case rather than a normal path — so the cheapest
 * correct rule is the right one. Ties resolve to [Resolution.USE_SERVER] because the
 * server copy is authoritative and this guarantees the client converges on the
 * server's value rather than ping-ponging.
 *
 * The one local condition that always wins is [localPendingDelete]: a user who
 * deleted a record offline expects it gone, and resurrecting it because the server
 * touched the row a moment earlier would be a data-loss surprise.
 */
object LastWriteWinsResolver {

    fun resolve(
        localUpdatedAt: String,
        serverUpdatedAt: String,
        localPendingDelete: Boolean,
    ): Resolution {
        // A user's offline delete is never silently undone.
        if (localPendingDelete) return Resolution.USE_LOCAL

        // No server timestamp to compare against: the pull delta is authoritative.
        if (serverUpdatedAt.isBlank()) return Resolution.USE_SERVER
        if (localUpdatedAt.isBlank()) return Resolution.USE_SERVER

        val local = parseEpochMillis(localUpdatedAt)
        val server = parseEpochMillis(serverUpdatedAt)
        if (local != null && server != null) {
            return if (local > server) Resolution.USE_LOCAL else Resolution.USE_SERVER
        }

        // Fallback for a format the parsers do not know. ISO-8601 UTC strings with a
        // fixed shape still sort chronologically as plain strings, so this degrades
        // safely instead of guessing.
        return if (localUpdatedAt.compareTo(serverUpdatedAt) > 0) {
            Resolution.USE_LOCAL
        } else {
            Resolution.USE_SERVER
        }
    }

    /**
     * Parses the timestamp variants this app actually emits/consumes: the server's
     * `...sssZ`, a no-millis `...ssZ`, an explicit numeric offset, and the date-only
     * form used by record dates. Returns `null` for anything else so the caller can
     * fall back to a string comparison. A fresh formatter per call keeps this
     * thread-safe without a lock; conflict resolution is not a hot path.
     */
    private fun parseEpochMillis(value: String): Long? {
        for (pattern in PATTERNS) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed = runCatching { formatter.parse(value) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    private val PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )
}
