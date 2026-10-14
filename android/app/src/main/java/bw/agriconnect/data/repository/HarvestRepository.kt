package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.HarvestRecordDao
import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.HarvestRecordEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.domain.model.HarvestRecord
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first repository for harvest records.
 */
interface HarvestRepository {
    fun observeAll(): Flow<List<HarvestRecord>>
    fun observeByFarmer(farmerId: String): Flow<List<HarvestRecord>>
    fun observeByPlot(plotId: String): Flow<List<HarvestRecord>>

    suspend fun getHarvest(harvestId: String): AppResult<HarvestRecord?>

    suspend fun saveHarvest(record: HarvestRecord): AppResult<HarvestRecord>
    suspend fun updateHarvest(record: HarvestRecord): AppResult<HarvestRecord>
    suspend fun deleteHarvest(harvestId: String): AppResult<Unit>

    suspend fun refresh(): AppResult<Unit>
}

@Singleton
internal class DefaultHarvestRepository @Inject constructor(
    private val dao: HarvestRecordDao,
    private val syncQueueDao: SyncQueueDao,
    private val api: ApiService,
    private val syncScheduler: SyncScheduler,
    private val errorParser: NetworkErrorParser,
    private val json: Json,
) : HarvestRepository {

    override fun observeAll(): Flow<List<HarvestRecord>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeByFarmer(farmerId: String): Flow<List<HarvestRecord>> = dao.observeByFarmer(farmerId).map { list -> list.map { it.toDomain() } }
    override fun observeByPlot(plotId: String): Flow<List<HarvestRecord>> = dao.observeByPlot(plotId).map { list -> list.map { it.toDomain() } }

    override suspend fun getHarvest(harvestId: String): AppResult<HarvestRecord?> = runCatchingApi { dao.findById(harvestId)?.toDomain() }

    override suspend fun saveHarvest(record: HarvestRecord): AppResult<HarvestRecord> {
        val now = nowIso()
        val clientId = record.clientId ?: UUID.randomUUID().toString()
        val harvestId = record.harvestId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val domain = record.copy(harvestId = harvestId, clientId = clientId, syncStatus = SyncStatus.PENDING, updatedAt = now, createdAt = record.createdAt.ifBlank { now })
        val entity = HarvestRecordEntity.fromDomain(domain)
        val payload = json.harvestPayload(domain)
        val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.HARVEST_RECORD, domain.harvestId, SyncOperation.CREATE, payload)
        return runCatchingApi {
            dao.insertAndEnqueue(entity, item)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun updateHarvest(record: HarvestRecord): AppResult<HarvestRecord> {
        val existing = dao.findById(record.harvestId)
        val now = nowIso()
        val domain = record.copy(syncStatus = SyncStatus.PENDING, updatedAt = now)
        return runCatchingApi {
            val op = if (existing?.syncStatus == SyncStatus.SYNCED) SyncOperation.UPDATE else {
                val queued = syncQueueDao.findByEntity(SyncQueueEntity.EntityTypes.HARVEST_RECORD, record.harvestId)
                if (queued?.operation == SyncOperation.CREATE) SyncOperation.CREATE else SyncOperation.UPDATE
            }
            val payload = json.harvestPayload(domain)
            val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.HARVEST_RECORD, domain.harvestId, op, payload)
            dao.insertAndEnqueue(HarvestRecordEntity.fromDomain(domain), item)
            syncQueueDao.dropPendingExcept(domain.harvestId, item.queueId)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun deleteHarvest(harvestId: String): AppResult<Unit> {
        val existing = dao.findById(harvestId) ?: return AppResult.Failure(notFoundError("Harvest record not found"))
        val now = nowIso()
        return runCatchingApi {
            if (syncQueueDao.serverMayHold(SyncQueueEntity.EntityTypes.HARVEST_RECORD, harvestId, existing.syncStatus)) {
                val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.HARVEST_RECORD, harvestId, SyncOperation.DELETE, "{}")
                dao.markPendingDeleteAndEnqueue(harvestId, now, item)
                syncQueueDao.dropPendingExcept(harvestId, item.queueId)
            } else {
                syncQueueDao.dropPendingFor(harvestId)
                dao.deleteById(harvestId)
            }
            syncScheduler.requestImmediate()
            Unit
        }
    }

    override suspend fun refresh(): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val resp = collectPages { page -> api.listHarvests(page = page, pageSize = REFRESH_PAGE_SIZE) }
        val entities = resp.map { dto -> HarvestRecordEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED) }
        dao.upsert(entities)
        Unit
    }
}
