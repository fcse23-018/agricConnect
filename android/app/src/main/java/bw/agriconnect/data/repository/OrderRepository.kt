package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.OrderDao
import bw.agriconnect.data.local.entity.OrderEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.CreateOrderRequest
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.domain.model.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Buyer + farmer orders.
 *
 * A single `GET /orders` returns the caller's orders in *either* role — a buyer sees
 * orders they placed, a farmer sees orders against their listings — so one cache and one
 * refresh covers both. Orders are created and transitioned server-side inside a stock
 * transaction, so nothing here enqueues sync work; the local table exists so the list and
 * detail screens stay usable offline.
 */
@Singleton
class OrderRepository @Inject constructor(
    private val api: ApiService,
    private val dao: OrderDao,
    private val errorParser: NetworkErrorParser,
) {

    /** All cached orders for the signed-in user, newest first. */
    fun observeAll(): Flow<List<Order>> = dao.observeAll().map { rows -> rows.map(OrderEntity::toDomain) }

    fun observeById(orderId: String): Flow<Order?> =
        dao.observeAll().map { rows -> rows.firstOrNull { it.orderId == orderId }?.toDomain() }

    /** Refreshes the cache. Rows are upserted (not wiped) so a detail view never blinks. */
    suspend fun refresh(): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val orders = collectPages { page -> api.listOrders(page = page, pageSize = REFRESH_PAGE_SIZE) }
        dao.upsert(orders.map { OrderEntity.fromDomain(it.toDomain()) })
        Unit
    }

    suspend fun getOrder(id: String): AppResult<Order> = runCatchingApi(errorParser::parse) {
        val order = api.getOrder(id).toDomain()
        dao.upsertOne(OrderEntity.fromDomain(order))
        order
    }

    suspend fun createOrder(listingId: String, quantity: Double, note: String? = null): AppResult<Order> =
        runCatchingApi(errorParser::parse) {
            val order = api.createOrder(body = CreateOrderRequest(listingId = listingId, quantity = quantity, note = note)).toDomain()
            dao.upsertOne(OrderEntity.fromDomain(order))
            order
        }

    suspend fun confirm(id: String): AppResult<Order> = transition(id) { api.confirmOrder(id) }
    suspend fun reject(id: String): AppResult<Order> = transition(id) { api.rejectOrder(id) }
    suspend fun cancel(id: String): AppResult<Order> = transition(id) { api.cancelOrder(id) }
    suspend fun complete(id: String): AppResult<Order> = transition(id) { api.completeOrder(id) }

    private suspend fun transition(
        id: String,
        call: suspend () -> bw.agriconnect.data.remote.dto.OrderDto,
    ): AppResult<Order> = runCatchingApi(errorParser::parse) {
        val order = call().toDomain()
        dao.upsertOne(OrderEntity.fromDomain(order))
        order
    }
}
