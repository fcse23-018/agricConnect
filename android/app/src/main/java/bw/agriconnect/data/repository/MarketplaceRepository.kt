package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.ProduceListingDao
import bw.agriconnect.data.local.entity.ProduceListingEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.CreateListingRequest
import bw.agriconnect.data.remote.dto.PageDto
import bw.agriconnect.data.remote.dto.UpdateListingRequest
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.ProduceListing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marketplace listings.
 *
 * **Why listings do not use the sync queue.** The `/sync/push` schema for
 * `ProduceListing` carries no `clientId` (see `sync.entities.js`), so a freshly created
 * listing cannot be reconciled idempotently the way farm work can. Create / publish /
 * archive therefore go straight over REST; only *reads* are cached so a buyer can still
 * browse the last-seen catalogue while offline. The cache is a convenience, never a
 * source of truth: a listing is only ever shown as PUBLIC once the server has confirmed
 * `PUBLISHED` (`ProduceListingDao.observePublished`).
 */
@Singleton
class MarketplaceRepository @Inject constructor(
    private val api: ApiService,
    private val dao: ProduceListingDao,
    private val errorParser: NetworkErrorParser,
) {

    /* --------------------------------- reads -------------------------------- */

    /** Buyer-facing catalogue, served from the offline cache. */
    fun observePublished(): Flow<List<ProduceListing>> =
        dao.observePublished().map { rows -> rows.map(ProduceListingEntity::toDomain) }

    /** The signed-in farmer's own listings (drafts included). */
    fun observeMine(farmerId: String): Flow<List<ProduceListing>> =
        dao.observeByFarmer(farmerId).map { rows -> rows.map(ProduceListingEntity::toDomain) }

    fun observeById(listingId: String): Flow<ProduceListing?> =
        dao.observeAll().map { rows -> rows.firstOrNull { it.listingId == listingId }?.toDomain() }

    /* -------------------------------- refresh ------------------------------- */

    /**
     * Pulls the public catalogue into the cache. When [search]/[crop] are supplied the
     * server returns a filtered page; the cache merge is still an upsert so a narrower
     * query never evicts listings the user already has.
     */
    suspend fun refreshPublished(search: String? = null, crop: String? = null): AppResult<Unit> =
        runCatchingApi(errorParser::parse) {
            val response = api.listListings(search = search, crop = crop, page = 1, pageSize = REFRESH_PAGE_SIZE)
            dao.upsert(response.items.map { ProduceListingEntity.fromDomain(it.toDomain()) })
            Unit
        }

    /** Pulls the farmer's own listings into the cache. */
    suspend fun refreshMine(): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val listings = collectPages { page -> api.myListings(page = page, pageSize = REFRESH_PAGE_SIZE) }
        dao.upsert(listings.map { ProduceListingEntity.fromDomain(it.toDomain()) })
        Unit
    }

    /* ------------------------------- mutations ------------------------------ */

    suspend fun getListing(id: String): AppResult<ProduceListing> =
        runCatchingApi(errorParser::parse) {
            val listing = api.getListing(id).toDomain()
            dao.upsertOne(ProduceListingEntity.fromDomain(listing))
            listing
        }

    suspend fun createListing(req: CreateListingRequest): AppResult<ProduceListing> =
        runCatchingApi(errorParser::parse) {
            val listing = api.createListing(req).toDomain()
            dao.upsertOne(ProduceListingEntity.fromDomain(listing))
            listing
        }

    suspend fun updateListing(id: String, req: UpdateListingRequest): AppResult<ProduceListing> =
        runCatchingApi(errorParser::parse) {
            val listing = api.patchListing(id, req).toDomain()
            dao.upsertOne(ProduceListingEntity.fromDomain(listing))
            listing
        }

    suspend fun publishListing(id: String): AppResult<ProduceListing> =
        runCatchingApi(errorParser::parse) {
            val listing = api.publishListing(id).toDomain()
            dao.upsertOne(ProduceListingEntity.fromDomain(listing))
            listing
        }

    suspend fun archiveListing(id: String): AppResult<ProduceListing> =
        runCatchingApi(errorParser::parse) {
            val listing = api.archiveListing(id).toDomain()
            dao.upsertOne(ProduceListingEntity.fromDomain(listing))
            listing
        }

    /** Convenience for the raw paged endpoint, used by tests/tools. */
    suspend fun listPage(search: String? = null, crop: String? = null, page: Int = 1, pageSize: Int = 20): AppResult<PageDto<bw.agriconnect.data.remote.dto.ListingDto>> =
        runCatchingApi(errorParser::parse) { api.listListings(search = search, crop = crop, page = page, pageSize = pageSize) }
}
