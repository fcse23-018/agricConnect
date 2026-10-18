package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppError
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.SatelliteNdviDao
import bw.agriconnect.data.local.entity.SatelliteNdviEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.SatelliteNdvi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Satellite NDVI observations.
 *
 * NDVI is always generated server-side (Earth Engine) and is never authored on the
 * device, so there is nothing to queue. The cache exists purely so a field worker with no
 * signal still sees the last observation for a plot. Critically, [latest] never
 * *fabricates* a reading: if the network fails and the cache is empty it returns the
 * failure, and if the cache has an older row that is returned with `isCached = true` so
 * the UI can label it honestly.
 */
@Singleton
class SatelliteRepository @Inject constructor(
    private val api: ApiService,
    private val dao: SatelliteNdviDao,
    private val errorParser: NetworkErrorParser,
) {

    fun observeByPlot(plotId: String): Flow<List<SatelliteNdvi>> =
        dao.observeByPlot(plotId).map { rows -> rows.map(SatelliteNdviEntity::toDomain) }

    fun observeByFarmer(farmerId: String): Flow<List<SatelliteNdvi>> =
        dao.observeByFarmer(farmerId).map { rows -> rows.map(SatelliteNdviEntity::toDomain) }

    /**
     * The newest observation for a plot, preferring the network and falling back to the
     * cached row when the request cannot reach the server. A `Success(null)` means the
     * server authoritatively has no observation yet (so the UI shows an empty state,
     * not an error).
     */
    suspend fun latest(plotId: String): AppResult<SatelliteNdvi?> {
        val network = runCatchingApi(errorParser::parse) { api.latestNdvi(plotId).ndvi?.toDomain() }
        return when (network) {
            is AppResult.Success -> {
                network.data?.let { dao.upsertOne(SatelliteNdviEntity.fromDomain(it)) }
                network
            }
            is AppResult.Failure -> {
                val cached = dao.findLatestForPlot(plotId)?.toDomain()
                if (cached != null) AppResult.Success(cached.copy(isCached = true)) else network
            }
        }
    }

    /** Pulls the NDVI observation history for a plot into the cache. */
    suspend fun refresh(plotId: String): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val rows = collectPages { page -> api.listNdvi(plotId = plotId, page = page, pageSize = REFRESH_PAGE_SIZE) }
        dao.upsert(rows.map { SatelliteNdviEntity.fromDomain(it.toDomain()) })
        Unit
    }

    /** Explicitly requests a fresh computation (server-side Earth Engine job). */
    suspend fun requestComputation(plotId: String, geoJsonBoundary: kotlinx.serialization.json.JsonElement? = null): AppResult<SatelliteNdvi> =
        runCatchingApi(errorParser::parse) {
            val ndvi = api.computeNdvi(bw.agriconnect.data.remote.dto.NdviRequest(plotId = plotId, geoJsonBoundary = geoJsonBoundary)).toDomain()
            dao.upsertOne(SatelliteNdviEntity.fromDomain(ndvi))
            ndvi
        }

    private companion object {
        const val REFRESH_PAGE_SIZE = 100
    }
}

/** Kept so callers can special-case connectivity without importing the code constants. */
val AppError.isOffline: Boolean get() = isNetwork
