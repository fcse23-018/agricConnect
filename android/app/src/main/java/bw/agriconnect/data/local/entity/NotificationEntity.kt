package bw.agriconnect.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import bw.agriconnect.domain.model.AppNotification

/**
 * Local cache of the server `notifications` table (backend/migrations/004). Notifications
 * are delivered/read server-side, so this table holds no sync bookkeeping.
 *
 * [userId] mirrors the server `user_id` column and scopes the cached feed per account. It
 * is not present on the domain [AppNotification] (docs/00 §5 G2 truncates that model), so
 * it defaults to `null` when mapping up and is populated by the sync/repository layer on
 * fetch. Keeping it here avoids cross-account leakage when a shared device switches users.
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["user_id", "created_at"]),
        Index(value = ["is_read"]),
    ],
)
data class NotificationEntity(
    @PrimaryKey
    @ColumnInfo(name = "notification_id")
    val notificationId: String,
    @ColumnInfo(name = "user_id")
    val userId: String? = null,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "related_entity_type")
    val relatedEntityType: String? = null,
    @ColumnInfo(name = "related_entity_id")
    val relatedEntityId: String? = null,
    @ColumnInfo(name = "is_read", defaultValue = "0")
    val isRead: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
) {
    fun toDomain(): AppNotification = AppNotification(
        notificationId = notificationId,
        type = type,
        title = title,
        body = body,
        relatedEntityType = relatedEntityType,
        relatedEntityId = relatedEntityId,
        isRead = isRead,
        createdAt = createdAt,
    )

    companion object {
        /** [userId] is passed separately because the domain model does not carry it. */
        fun fromDomain(domain: AppNotification, userId: String? = null): NotificationEntity =
            NotificationEntity(
                notificationId = domain.notificationId,
                userId = userId,
                type = domain.type,
                title = domain.title,
                body = domain.body,
                relatedEntityType = domain.relatedEntityType,
                relatedEntityId = domain.relatedEntityId,
                isRead = domain.isRead,
                createdAt = domain.createdAt,
            )
    }
}
