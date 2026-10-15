package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.NotificationDao
import bw.agriconnect.data.local.entity.NotificationEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.AppNotification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-app notification feed.
 *
 * Notifications are produced server-side; the client only ever flips the read flag. Read
 * state is applied locally first (so the badge and list update instantly) and then pushed
 * best-effort — a failed push is not retried through the sync queue because the server
 * remains authoritative and the next successful refresh restores the true state. That
 * keeps a transient network blip from surfacing as a scary "not synced" marker on a
 * purely cosmetic action.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val api: ApiService,
    private val dao: NotificationDao,
    private val errorParser: NetworkErrorParser,
) {

    fun observeAll(): Flow<List<AppNotification>> =
        dao.observeAll().map { rows -> rows.map(NotificationEntity::toDomain) }

    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    /** Refreshes the cache from the server. */
    suspend fun refresh(userId: String? = null): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val response = api.listNotifications(page = 1, pageSize = REFRESH_PAGE_SIZE)
        dao.upsert(response.items.map { NotificationEntity.fromDomain(it.toDomain(), userId) })
        Unit
    }

    /**
     * Optimistically marks one notification read, then best-effort pushes it. The local
     * write is what the UI observes, so a push failure still leaves the user with the
     * state they asked for.
     */
    suspend fun markRead(id: String): AppResult<Unit> {
        dao.markRead(id)
        return runCatchingApi(errorParser::parse) { api.markRead(id); Unit }
    }

    /** Optimistically marks every notification read, then best-effort pushes it. */
    suspend fun markAllRead(): AppResult<Unit> {
        dao.markAllRead()
        return runCatchingApi(errorParser::parse) { api.markAllRead(); Unit }
    }

    fun observeUnreadOnly(): Flow<List<AppNotification>> =
        dao.observeUnread().map { rows -> rows.map(NotificationEntity::toDomain) }

    /** Clears the cached feed (sign-out / account switch). */
    suspend fun clear() = dao.deleteAll()
}
