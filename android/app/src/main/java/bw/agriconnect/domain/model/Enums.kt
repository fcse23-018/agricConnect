package bw.agriconnect.domain.model

/**
 * Domain enums. These mirror the documented backend enums exactly
 * (docs/02-API-CONTRACT.md §1). Byte-for-byte they match the PostgreSQL types so
 * no translation is needed on the wire.
 */

enum class UserRole {
    FARMER, FARM_EMPLOYEE, BUYER;

    val displayName: String
        get() = when (this) {
            FARMER -> "Farmer"
            FARM_EMPLOYEE -> "Farm Employee"
            BUYER -> "Buyer"
        }
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

enum class Unit(val label: String) {
    KG("kg"), TONNE("tonne"), BAG("bag"), CRATE("crate"), BUNCH("bunch"), HEAD("head"), LITRE("litre");

    companion object {
        fun fromWire(value: String?): Unit = entries.firstOrNull { it.name == value } ?: KG
    }
}
