package bw.agriconnect.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import bw.agriconnect.data.local.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

/**
 * In-app notification feed cache.
 *
 * Notifications are produced and marked-read server-side, so there is no sync queue here.
 * The unread count is a `Flow` so the tab badge updates the instant [markRead]/[markAllRead]
 * commits. Read-state changes are persisted locally and pushed via the repository; they are
 * not enqueued as farm work.
 */
@Dao
abstract class NotificationDao {

    @Query("SELECT * FROM notifications ORDER BY created_at DESC")
    abstract fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE is_read = 0 ORDER BY created_at DESC")
    abstract fun observeUnread(): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE is_read = 0")
    abstract fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM notifications WHERE notification_id = :notificationId LIMIT 1")
    abstract suspend fun findById(notificationId: String): NotificationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entities: List<NotificationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOne(entity: NotificationEntity)

    @Query("UPDATE notifications SET is_read = 1 WHERE notification_id = :notificationId")
    abstract suspend fun markRead(notificationId: String)

    @Query("UPDATE notifications SET is_read = 1 WHERE is_read = 0")
    abstract suspend fun markAllRead()

    @Query("DELETE FROM notifications WHERE notification_id = :notificationId")
    abstract suspend fun deleteById(notificationId: String)

    @Query("DELETE FROM notifications")
    abstract suspend fun deleteAll()

    /** Atomically swaps the cached feed for a freshly-pulled page. */
    @Transaction
    open suspend fun replaceAll(entities: List<NotificationEntity>) {
        deleteAll()
        if (entities.isNotEmpty()) upsert(entities)
    }
}
