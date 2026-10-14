package bw.agriconnect.data.local

import androidx.room.TypeConverter
import bw.agriconnect.domain.model.AccountStatus
import bw.agriconnect.domain.model.DeletionRequestStatus
import bw.agriconnect.domain.model.ListingStatus
import bw.agriconnect.domain.model.NdviProcessingStatus
import bw.agriconnect.domain.model.NotificationPreferences
import bw.agriconnect.domain.model.OrderStatus
import bw.agriconnect.domain.model.StressLevel
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import bw.agriconnect.domain.model.UserRole
import kotlin.enums.enumEntries
import bw.agriconnect.domain.model.Unit as DomainUnit

/**
 * Room [TypeConverter]s for this database.
 *
 * Enums are persisted by [Enum.name] (never ordinal) for two reasons:
 *  1. the DB stays human-readable, which matters when debugging an offline queue on a
 *     user's device, and
 *  2. the stored value is byte-for-byte the same token the REST API uses on the wire
 *     (docs/02-API-CONTRACT.md §1), so no translation layer is needed for sync.
 *
 * Every converter is declared with a nullable parameter type: Room reuses a nullable
 * converter for non-null columns by wrapping the call with a null check, so one converter
 * per enum covers both nullable and non-null fields.
 *
 * Unknown tokens decode to `null` rather than throwing. A database written by an older
 * app version can therefore still be read (the offending value degrades to null) instead
 * of crashing the app on startup.
 */
class Converters {

    // --- UserRole -----------------------------------------------------------
    @TypeConverter
    fun fromUserRole(value: UserRole?): String? = value?.name

    @TypeConverter
    fun toUserRole(value: String?): UserRole? = decodeEnum<UserRole>(value)

    // --- AccountStatus ------------------------------------------------------
    @TypeConverter
    fun fromAccountStatus(value: AccountStatus?): String? = value?.name

    @TypeConverter
    fun toAccountStatus(value: String?): AccountStatus? = decodeEnum<AccountStatus>(value)

    // --- SyncStatus ---------------------------------------------------------
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus?): String? = value?.name

    @TypeConverter
    fun toSyncStatus(value: String?): SyncStatus? = decodeEnum<SyncStatus>(value)

    // --- SyncOperation ------------------------------------------------------
    @TypeConverter
    fun fromSyncOperation(value: SyncOperation?): String? = value?.name

    @TypeConverter
    fun toSyncOperation(value: String?): SyncOperation? = decodeEnum<SyncOperation>(value)

    // --- ListingStatus ------------------------------------------------------
    @TypeConverter
    fun fromListingStatus(value: ListingStatus?): String? = value?.name

    @TypeConverter
    fun toListingStatus(value: String?): ListingStatus? = decodeEnum<ListingStatus>(value)

    // --- OrderStatus --------------------------------------------------------
    @TypeConverter
    fun fromOrderStatus(value: OrderStatus?): String? = value?.name

    @TypeConverter
    fun toOrderStatus(value: String?): OrderStatus? = decodeEnum<OrderStatus>(value)

    // --- StressLevel --------------------------------------------------------
    @TypeConverter
    fun fromStressLevel(value: StressLevel?): String? = value?.name

    @TypeConverter
    fun toStressLevel(value: String?): StressLevel? = decodeEnum<StressLevel>(value)

    // --- NdviProcessingStatus ----------------------------------------------
    @TypeConverter
    fun fromNdviProcessingStatus(value: NdviProcessingStatus?): String? = value?.name

    @TypeConverter
    fun toNdviProcessingStatus(value: String?): NdviProcessingStatus? = decodeEnum<NdviProcessingStatus>(value)

    // --- Unit (aliased to avoid clashing with kotlin.Unit) ------------------
    @TypeConverter
    fun fromUnit(value: DomainUnit?): String? = value?.name

    @TypeConverter
    fun toUnit(value: String?): DomainUnit? = decodeEnum<DomainUnit>(value)

    // --- DeletionRequestStatus (account-deletion cache) ---------------------
    @TypeConverter
    fun fromDeletionRequestStatus(value: DeletionRequestStatus?): String? = value?.name

    @TypeConverter
    fun toDeletionRequestStatus(value: String?): DeletionRequestStatus? = decodeEnum<DeletionRequestStatus>(value)

    // --- NotificationPreferences -------------------------------------------
    // Stored as a compact "orders:1,sync:1,satellite:0,marketplace:1" string. This keeps
    // the four flags readable in the DB and avoids pulling in a JSON dependency just for
    // one value object (docs/00 §5 G1 places these on the user profile).
    @TypeConverter
    fun fromNotificationPreferences(value: NotificationPreferences?): String? =
        value?.let(NotificationPreferencesCodec::encode)

    @TypeConverter
    fun toNotificationPreferences(value: String?): NotificationPreferences? =
        value?.let(NotificationPreferencesCodec::decode)

    /**
     * Resolves an enum by its [Enum.name], returning `null` for `null`/unknown input.
     * [enumEntries] is used instead of the deprecated `values()` so no array is cloned.
     */
    private inline fun <reified T : Enum<T>> decodeEnum(value: String?): T? =
        value?.let { raw -> enumEntries<T>().firstOrNull { it.name == raw } }
}

/**
 * Encoder/decoder for [NotificationPreferences]. Kept separate from the Room converters so
 * it can be unit-tested without an Android/Room dependency.
 *
 * Decoding is deliberately lenient: unknown keys are ignored and missing keys fall back to
 * the domain defaults (all `true`). This means a partially-written or forward-compatible
 * string never loses the user's settings.
 */
internal object NotificationPreferencesCodec {

    fun encode(prefs: NotificationPreferences): String = listOf(
        "orders" to prefs.orders,
        "sync" to prefs.sync,
        "satellite" to prefs.satellite,
        "marketplace" to prefs.marketplace,
    ).joinToString(",") { (key, value) -> "$key:${if (value) 1 else 0}" }

    fun decode(raw: String): NotificationPreferences {
        val flags: Map<String, String> = raw.split(',')
            .mapNotNull { token ->
                val parts = token.split(':')
                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
            }
            .toMap()

        fun flag(key: String): Boolean = when (flags[key]?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> true // missing/blank -> domain default
        }

        return NotificationPreferences(
            orders = flag("orders"),
            sync = flag("sync"),
            satellite = flag("satellite"),
            marketplace = flag("marketplace"),
        )
    }
}
