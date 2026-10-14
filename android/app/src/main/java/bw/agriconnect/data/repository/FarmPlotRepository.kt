package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.FarmPlotDao
import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.FarmPlotEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.domain.model.FarmPlot
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first repository for farm plots.
 */
interface FarmPlotRepository {
    fun observeAll(): Flow<List<FarmPlot>>
    fun observeByFarmer(farmerId: String): Flow<List<FarmPlot>>
    fun observeCount(): Flow<Int>

    suspend fun getPlot(plotId: String): AppResult<FarmPlot?>

    suspend fun savePlot(plot: FarmPlot): AppResult<FarmPlot>
    suspend fun updatePlot(plot: FarmPlot): AppResult<FarmPlot>
    suspend fun deletePlot(plotId: String): AppResult<Unit>

    suspend fun refresh(): AppResult<Unit>
}

@Singleton
internal class DefaultFarmPlotRepository @Inject constructor(
    private val plotDao: FarmPlotDao,
    private val syncQueueDao: SyncQueueDao,
    private val api: ApiService,
    private val syncScheduler: SyncScheduler,
    private val errorParser: NetworkErrorParser,
    private val json: Json,
) : FarmPlotRepository {

    override fun observeAll(): Flow<List<FarmPlot>> = plotDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByFarmer(farmerId: String): Flow<List<FarmPlot>> = plotDao.observeByFarmer(farmerId).map { list -> list.map { it.toDomain() } }

    override fun observeCount(): Flow<Int> = plotDao.observeCount()

    override suspend fun getPlot(plotId: String): AppResult<FarmPlot?> = runCatchingApi {
        plotDao.findById(plotId)?.toDomain()
    }

    override suspend fun savePlot(plot: FarmPlot): AppResult<FarmPlot> {
        val now = nowIso()
        val clientId = plot.clientId ?: UUID.randomUUID().toString()
        val plotId = plot.plotId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val domain = plot.copy(plotId = plotId, clientId = clientId, syncStatus = SyncStatus.PENDING, updatedAt = now, createdAt = plot.createdAt.ifBlank { now })
        val entity = FarmPlotEntity.fromDomain(domain)
        val payload = json.farmPlotPayload(domain)
        val item = newSyncQueueItem(entityType = SyncQueueEntity.EntityTypes.FARM_PLOT, entityId = domain.plotId, operation = SyncOperation.CREATE, payload = payload)
        return runCatchingApi {
            plotDao.insertAndEnqueue(entity, item)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun updatePlot(plot: FarmPlot): AppResult<FarmPlot> {
        val existing = plotDao.findById(plot.plotId)
        val now = nowIso()
        val domain = plot.copy(syncStatus = SyncStatus.PENDING, updatedAt = now)
        val entity = FarmPlotEntity.fromDomain(domain)
        return runCatchingApi {
            val op = if (existing?.syncStatus == SyncStatus.SYNCED) SyncOperation.UPDATE else {
                val queued = syncQueueDao.findByEntity(SyncQueueEntity.EntityTypes.FARM_PLOT, plot.plotId)
                if (queued?.operation == SyncOperation.CREATE) SyncOperation.CREATE else SyncOperation.UPDATE
            }
            val payload = json.farmPlotPayload(domain)
            val item = newSyncQueueItem(entityType = SyncQueueEntity.EntityTypes.FARM_PLOT, entityId = domain.plotId, operation = op, payload = payload)
            plotDao.insertAndEnqueue(entity, item)
            syncQueueDao.dropPendingExcept(domain.plotId, item.queueId)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun deletePlot(plotId: String): AppResult<Unit> {
        val existing = plotDao.findById(plotId) ?: return AppResult.Failure(notFoundError("Plot not found"))
        val now = nowIso()
        return runCatchingApi {
            if (syncQueueDao.serverMayHold(SyncQueueEntity.EntityTypes.FARM_PLOT, plotId, existing.syncStatus)) {
                val payload = "{}"
                val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.FARM_PLOT, plotId, SyncOperation.DELETE, payload)
                plotDao.markPendingDeleteAndEnqueue(plotId, now, item)
                syncQueueDao.dropPendingExcept(plotId, item.queueId)
            } else {
                syncQueueDao.dropPendingFor(plotId)
                plotDao.deleteById(plotId)
            }
            syncScheduler.requestImmediate()
            Unit
        }
    }

    override suspend fun refresh(): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val resp = api.listPlots()
        val items = resp.items
        val entities = items.map { dto -> FarmPlotEntity.fromDomain(dto.toDomain()).copy(syncStatus = SyncStatus.SYNCED) }
        plotDao.upsert(entities)
        Unit
    }
}
