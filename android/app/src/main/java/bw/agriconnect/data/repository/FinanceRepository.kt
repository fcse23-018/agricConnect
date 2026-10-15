package bw.agriconnect.data.repository

import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.runCatchingApi
import bw.agriconnect.data.local.dao.ExpenseDao
import bw.agriconnect.data.local.dao.SaleTransactionDao
import bw.agriconnect.data.local.dao.SyncQueueDao
import bw.agriconnect.data.local.entity.ExpenseEntity
import bw.agriconnect.data.local.entity.SaleTransactionEntity
import bw.agriconnect.data.local.entity.SyncQueueEntity
import bw.agriconnect.data.remote.ApiService
import bw.agriconnect.data.remote.NetworkErrorParser
import bw.agriconnect.data.remote.dto.toDomain
import bw.agriconnect.data.sync.SyncScheduler
import bw.agriconnect.domain.model.Expense
import bw.agriconnect.domain.model.FinanceSummary
import bw.agriconnect.domain.model.SaleTransaction
import bw.agriconnect.domain.model.SyncOperation
import bw.agriconnect.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first repository for the farmer-only finance domain: sales, expenses and the
 * server-computed summary.
 *
 * Sales and expenses are authored on the device, so they follow the exact same
 * write-and-enqueue pattern as [FarmPlotRepository]: the row and its outbox entry commit in
 * one Room transaction, then a sync is requested. The summary is a server aggregate and is
 * never cached as if it were local truth — a failed summary call is surfaced, not faked.
 */
interface FinanceRepository {
    fun observeSales(farmerId: String): Flow<List<SaleTransaction>>
    fun observeExpenses(farmerId: String): Flow<List<Expense>>

    suspend fun saveSale(sale: SaleTransaction): AppResult<SaleTransaction>
    suspend fun updateSale(sale: SaleTransaction): AppResult<SaleTransaction>
    suspend fun deleteSale(transactionId: String): AppResult<Unit>

    suspend fun saveExpense(expense: Expense): AppResult<Expense>
    suspend fun updateExpense(expense: Expense): AppResult<Expense>
    suspend fun deleteExpense(expenseId: String): AppResult<Unit>

    suspend fun refresh(): AppResult<Unit>
    suspend fun summary(from: String? = null, to: String? = null): AppResult<FinanceSummary>
}

@Singleton
internal class DefaultFinanceRepository @Inject constructor(
    private val saleDao: SaleTransactionDao,
    private val expenseDao: ExpenseDao,
    private val syncQueueDao: SyncQueueDao,
    private val api: ApiService,
    private val syncScheduler: SyncScheduler,
    private val errorParser: NetworkErrorParser,
    private val json: Json,
) : FinanceRepository {

    override fun observeSales(farmerId: String): Flow<List<SaleTransaction>> =
        saleDao.observeByFarmer(farmerId).map { list -> list.map { it.toDomain() } }

    override fun observeExpenses(farmerId: String): Flow<List<Expense>> =
        expenseDao.observeByFarmer(farmerId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveSale(sale: SaleTransaction): AppResult<SaleTransaction> {
        val now = nowIso()
        val clientId = sale.clientId ?: UUID.randomUUID().toString()
        val id = sale.transactionId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val domain = sale.copy(
            transactionId = id,
            clientId = clientId,
            syncStatus = SyncStatus.PENDING,
            updatedAt = now,
            createdAt = sale.createdAt.ifBlank { now },
        )
        val payload = json.salePayload(domain)
        val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.SALE_TRANSACTION, id, SyncOperation.CREATE, payload)
        return runCatchingApi {
            saleDao.insertAndEnqueue(SaleTransactionEntity.fromDomain(domain), item)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun updateSale(sale: SaleTransaction): AppResult<SaleTransaction> {
        val existing = saleDao.findById(sale.transactionId)
        val now = nowIso()
        val domain = sale.copy(syncStatus = SyncStatus.PENDING, updatedAt = now)
        return runCatchingApi {
            val op = if (existing?.syncStatus == SyncStatus.SYNCED) SyncOperation.UPDATE else {
                val queued = syncQueueDao.findByEntity(SyncQueueEntity.EntityTypes.SALE_TRANSACTION, sale.transactionId)
                if (queued?.operation == SyncOperation.CREATE) SyncOperation.CREATE else SyncOperation.UPDATE
            }
            val item = newSyncQueueItem(
                SyncQueueEntity.EntityTypes.SALE_TRANSACTION, domain.transactionId, op, json.salePayload(domain),
            )
            saleDao.insertAndEnqueue(SaleTransactionEntity.fromDomain(domain), item)
            syncQueueDao.dropPendingExcept(domain.transactionId, item.queueId)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun deleteSale(transactionId: String): AppResult<Unit> {
        val existing = saleDao.findById(transactionId) ?: return AppResult.Failure(notFoundError("Sale not found"))
        val now = nowIso()
        return runCatchingApi {
            if (syncQueueDao.serverMayHold(SyncQueueEntity.EntityTypes.SALE_TRANSACTION, transactionId, existing.syncStatus)) {
                val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.SALE_TRANSACTION, transactionId, SyncOperation.DELETE, "{}")
                saleDao.markPendingDeleteAndEnqueue(transactionId, now, item)
                syncQueueDao.dropPendingExcept(transactionId, item.queueId)
            } else {
                syncQueueDao.dropPendingFor(transactionId)
                saleDao.deleteById(transactionId)
            }
            syncScheduler.requestImmediate()
            Unit
        }
    }

    override suspend fun saveExpense(expense: Expense): AppResult<Expense> {
        val now = nowIso()
        val clientId = expense.clientId ?: UUID.randomUUID().toString()
        val id = expense.expenseId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val domain = expense.copy(
            expenseId = id,
            clientId = clientId,
            syncStatus = SyncStatus.PENDING,
            updatedAt = now,
            createdAt = expense.createdAt.ifBlank { now },
        )
        val payload = json.expensePayload(domain)
        val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.EXPENSE, id, SyncOperation.CREATE, payload)
        return runCatchingApi {
            expenseDao.insertAndEnqueue(ExpenseEntity.fromDomain(domain), item)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun updateExpense(expense: Expense): AppResult<Expense> {
        val existing = expenseDao.findById(expense.expenseId)
        val now = nowIso()
        val domain = expense.copy(syncStatus = SyncStatus.PENDING, updatedAt = now)
        return runCatchingApi {
            val op = if (existing?.syncStatus == SyncStatus.SYNCED) SyncOperation.UPDATE else {
                val queued = syncQueueDao.findByEntity(SyncQueueEntity.EntityTypes.EXPENSE, expense.expenseId)
                if (queued?.operation == SyncOperation.CREATE) SyncOperation.CREATE else SyncOperation.UPDATE
            }
            val item = newSyncQueueItem(
                SyncQueueEntity.EntityTypes.EXPENSE, domain.expenseId, op, json.expensePayload(domain),
            )
            expenseDao.insertAndEnqueue(ExpenseEntity.fromDomain(domain), item)
            syncQueueDao.dropPendingExcept(domain.expenseId, item.queueId)
            syncScheduler.requestImmediate()
            domain
        }
    }

    override suspend fun deleteExpense(expenseId: String): AppResult<Unit> {
        val existing = expenseDao.findById(expenseId) ?: return AppResult.Failure(notFoundError("Expense not found"))
        val now = nowIso()
        return runCatchingApi {
            if (syncQueueDao.serverMayHold(SyncQueueEntity.EntityTypes.EXPENSE, expenseId, existing.syncStatus)) {
                val item = newSyncQueueItem(SyncQueueEntity.EntityTypes.EXPENSE, expenseId, SyncOperation.DELETE, "{}")
                expenseDao.markPendingDeleteAndEnqueue(expenseId, now, item)
                syncQueueDao.dropPendingExcept(expenseId, item.queueId)
            } else {
                syncQueueDao.dropPendingFor(expenseId)
                expenseDao.deleteById(expenseId)
            }
            syncScheduler.requestImmediate()
            Unit
        }
    }

    override suspend fun refresh(): AppResult<Unit> = runCatchingApi(errorParser::parse) {
        val sales = collectPages { page -> api.listSales(page = page, pageSize = REFRESH_PAGE_SIZE) }
        saleDao.upsert(sales.map { SaleTransactionEntity.fromDomain(it.toDomain()).copy(syncStatus = SyncStatus.SYNCED) })
        val expenses = collectPages { page -> api.listExpenses(page = page, pageSize = REFRESH_PAGE_SIZE) }
        expenseDao.upsert(expenses.map { ExpenseEntity.fromDomain(it.toDomain()).copy(syncStatus = SyncStatus.SYNCED) })
        Unit
    }

    override suspend fun summary(from: String?, to: String?): AppResult<FinanceSummary> =
        runCatchingApi(errorParser::parse) { api.financeSummary(from, to).toDomain() }
}
