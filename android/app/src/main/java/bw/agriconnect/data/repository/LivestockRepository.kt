package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.LivestockCountDao
import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.LivestockCountEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.domain.model.LivestockCount
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface LivestockRepository {
    fun observeAll(): Flow<List<LivestockCount>>
    fun observeByFarmer(farmerId: String): Flow<List<LivestockCount>>

    suspend fun getLivestock(countId: String): AppResult<LivestockCount?>
    suspend fun saveLivestock(count: LivestockCount): AppResult<LivestockCount>
    suspend fun updateLivestock(count: LivestockCount): AppResult<LivestockCount>
    suspend fun deleteLivestock(countId: String): AppResult<Unit>

    suspend fun refresh(): AppResult<Unit>
}

@Singleton
internal class DefaultLivestockRepository @Inject constructor(
    private val dao: LivestockCountDao,
    private val syncQueueDao: SyncQueueDao,
    private val api: ApiService,
    private val syncScheduler: SyncScheduler,
    private val errorParser: NetworkErrorParser,
    private val json: Json,
) : LivestockRepository {

    override fun observeAll(): Flow<List<LivestockCount>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeByFarmer(farmerId: String): Flow<List<LivestockCount>> = dao.observeByFarmer(farmerId).map { list -> list.map { it.toDomain() } }

    override suspend fun getLivestock(countId: String): AppResult<LivestockCount?> = runCatchingApi { dao.findById(countId)?.toDomain() }

    override suspend fun saveLivestock(count: LivestockCount): AppResult<LivestockCount> {
        val now = nowIso()
        val clientId = count.clientId ?: UUID.randomUUID().toString()
        val countId = count.countId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val domain = count.copy(countId = countId, clientId = clientId, syncStatus = SyncStatus.PENDING, updatedAt = now, createdAt = count.createdAt.ifBlank { now })
        val entity = LivestockCountEntity.fromDomain(domain)
        val payload = json.livestockPayload(domain)
        val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.LIVESTOCK_COUNT, domain.countId, SyncOperation.CREATE, payload)
        return runCatchingApi {
            dao.insertAndEnqueue(entity, item)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun updateLivestock(count: LivestockCount): AppResult<LivestockCount> {
        val existing = dao.findById(count.countId)
        val now = nowIso()
        val domain = count.copy(syncStatus = SyncStatus.PENDING, updatedAt = now)
        return runCatchingApi {
            val op = if (existing?.syncStatus == SyncStatus.SYNCED) SyncOperation.UPDATE else {
                val queued = syncQueueDao.findByEntity(SyncQueueEntity.EntityTypes.LIVESTOCK_COUNT, count.countId)
                if (queued?.operation == SyncOperation.CREATE) SyncOperation.CREATE else SyncOperation.UPDATE
            }
            val payload = json.livestockPayload(domain)
            val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.LIVESTOCK_COUNT, domain.countId, op, payload)
            dao.insertAndEnqueue(LivestockCountEntity.fromDomain(domain), item)
            syncQueueDao.dropPendingExcept(domain.countId, item.queueId)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun deleteLivestock(countId: String): AppResult<Unit> {
        val existing = dao.findById(countId) ?: return AppResult.Failure(notFoundError("Livestock count not found"))
        val now = nowIso()
        return runCatchingApi {
            if (syncQueueDao.serverMayHold(SyncQueueEntity.EntityTypes.LIVESTOCK_COUNT, countId, existing.syncStatus)) {
                val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.LIVESTOCK_COUNT, countId, SyncOperation.DELETE, "{}")
                dao.markPendingDeleteAndEnqueue(countId, now, item)
                syncQueueDao.dropPendingExcept(countId, item.queueId)
            } else {
                syncQueueDao.dropPendingFor(countId)
                dao.deleteById(countId)
            }
            syncScheduler.requestImmediate()
            Unit
        }
    }

    override suspend fun refresh(): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val resp = collectPages { page -> api.listLivestock(page = page, pageSize = REFRESH_PAGE_SIZE) }
        val entities = resp.map { dto -> LivestockCountEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED) }
        dao.upsert(entities)
        Unit
    }
}
