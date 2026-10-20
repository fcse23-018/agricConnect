package bw.agriconnect.domain.model

/**
 * Domain enums. These mirror the documented backend enums exactly
 * (docs/02-API-CONTRACT.md §1). Byte-for-byte they match the PostgreSQL types so
 * no translation is needed on the wire.
 */

enum class UserRole {
    FARM_OWNER, FARMER, BUYER;

    val displayName: String
        get() = when (this) {
            FARM_OWNER -> "Farm Owner"
            FARMER -> "Farmer"
            BUYER -> "Buyer"
        }

    /**
     * What a role is for, mirroring the backend permission matrix
     * (`backend/src/middleware/roles.js`). The client uses these to *hide* what the
     * server would refuse with a 403 — the server is still the enforcement point, so a
     * hidden control can never grant access and a visible one can never be a lie.
     */
    /** Owns the farm: maps plots, sees finances, computes NDVI and sells produce. */
    val ownsFarm: Boolean get() = this == FARM_OWNER

    /** Works the land: records field data against the owner's farm. */
    val worksTheLand: Boolean get() = this == FARMER

    /** The owner and the farmer can see plots; a buyer has no farm at all. */
    val canViewFarms: Boolean get() = this != BUYER

    /** Only the owner may create, edit or delete a plot. */
    val canManageFarms: Boolean get() = this == FARM_OWNER

    /** The owner and the farmer record harvests and livestock. */
    val canRecordFieldData: Boolean get() = this != BUYER

    val canSeeFinance: Boolean get() = this == FARM_OWNER

    val canComputeNdvi: Boolean get() = this == FARM_OWNER

    /** Only the owner lists produce for sale. */
    val canSell: Boolean get() = this == FARM_OWNER

    /** Every role may buy — agriculture is a two-sided market. */
    val canBuy: Boolean get() = true
}

enum class AccountStatus {
    REGISTERED, UNVERIFIED, VERIFIED, SUSPENDED, PENDING_DELETION, DELETED;

    val isActive: Boolean get() = this == VERIFIED
    val needsVerification: Boolean get() = this == UNVERIFIED || this == REGISTERED
}

enum class SyncStatus {
    /** Saved locally, waiting for connectivity. */
    PENDING,
    /** Currently uploading. */
    SYNCING,
    /** Server acknowledged. */
    SYNCED,
    /** Server copy was newer; needs resolution. */
    CONFLICT,
    /** Rejected by the server (validation/authorisation). */
    FAILED,
}

enum class SyncOperation { CREATE, UPDATE, DELETE }

/** Documented listing lifecycle (Architecture v1.1 — six states). */
enum class ListingStatus {
    DRAFT, PENDING_PUBLISH, SYNCING, RETRY, PUBLISHED, SOLD;

    val isPubliclyVisible: Boolean get() = this == PUBLISHED
}

enum class OrderStatus { PENDING, CONFIRMED, COMPLETED, CANCELLED }

enum class StressLevel { HIGH_STRESS, MODERATE_STRESS, HEALTHY_VEGETATION }

enum class NdviProcessingStatus { REQUESTED, FETCHING_IMAGERY, COMPUTING_NDVI, GENERATED, FAILED }

/**
 * How urgently the post-reading guidance should be read. Mirrors the backend's
 * `AdvicePriority` so the client never re-interprets the server's judgement.
 */
enum class NdviAdvicePriority {
    NOW, SOON, ROUTINE;

    val displayName: String
        get() = when (this) {
            NOW -> "Act now"
            SOON -> "Watch closely"
            ROUTINE -> "Keep going"
        }
}

/** Direction of travel versus the plot's previous reading (backend `TrendDirection`). */
enum class NdviTrendDirection { FIRST_READING, IMPROVING, DECLINING, STABLE }

/**
 * What a listing is measured in — and the wire names the server accepts.
 *
 * The order is deliberate: produce first (it is what most listings are), then the
 * livestock units, because they answer a different question — "how many animals"
 * rather than "how much". `HEAD` is one animal (`20 head`); `HERD` and `FLOCK` are
 * a whole group sold as one lot (`1 herd`). The list must stay identical to
 * `LISTING_UNITS` on the server, or a listing is rejected at sync time.
 */
enum class Unit(val label: String, val livestock: Boolean = false) {
    KG("kg"),
    TONNE("tonne"),
    BAG("bag"),
    CRATE("crate"),
    BUNCH("bunch"),
    LITRE("litre"),
    HEAD("head", livestock = true),
    HERD("herd", livestock = true),
    FLOCK("flock", livestock = true);

    /** The value the API expects (the enum name, lower-cased). */
    val wire: String get() = name.lowercase(java.util.Locale.US)

    companion object {
        fun fromWire(value: String?): Unit =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: KG
    }
}
