package bw.agriconnect.ui.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bw.agriconnect.core.result.AppResult
import bw.agriconnect.core.result.onSuccess
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.data.repository.FinanceRepository
import bw.agriconnect.domain.model.Expense
import bw.agriconnect.domain.model.FinancePoint
import bw.agriconnect.domain.model.FinanceSummary
import bw.agriconnect.domain.model.SaleTransaction
import bw.agriconnect.domain.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

data class FinanceUiState(
    val summary: FinanceSummary? = null,
    val sales: List<SaleTransaction> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedTab: Int = 0, // 0 = Overview, 1 = Sales, 2 = Expenses
    val showAddDialog: Boolean = false,
    val addType: AddType = AddType.SALE,
    val isSaving: Boolean = false,
    val formError: String? = null,
    // Sale form
    val saleItem: String = "",
    val saleQuantity: String = "",
    val saleUnitPrice: String = "",
    val saleAmount: String = "",
    val saleBuyer: String = "",
    val saleDate: String = "",
    val saleNotes: String = "",
    // Expense form
    val expenseCategory: String = "",
    val expenseAmount: String = "",
    val expenseDescription: String = "",
    val expenseDate: String = "",
) {
    val netBalance: Double get() = summary?.netBalance ?: (totalSales - totalExpenses)
    val totalSales: Double get() = summary?.totalSales ?: sales.sumOf { it.amount }
    val totalExpenses: Double get() = summary?.totalExpenses ?: expenses.sumOf { it.amount }
    val currency: String get() = summary?.currency ?: "BWP"
    val points: List<FinancePoint> get() = summary?.series ?: emptyList()
}

enum class AddType { SALE, EXPENSE }

/** Current UTC timestamp in ISO8601 milliseconds format. */
private fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date())

@HiltViewModel
class FinanceViewModel @Inject constructor(
    private val repository: FinanceRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FinanceUiState())
    val state: StateFlow<FinanceUiState> = _state.asStateFlow()

    val session: StateFlow<Session?> = authRepository.session

    private val farmerId: StateFlow<String?> = session.map { it?.userId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            farmerId.collect { id ->
                if (id != null) {
                    repository.observeSales(id).collect { sales ->
                        _state.update { it.copy(sales = sales.sortedByDescending { s -> s.date }) }
                    }
                }
            }
        }
        viewModelScope.launch {
            farmerId.collect { id ->
                if (id != null) {
                    repository.observeExpenses(id).collect { expenses ->
                        _state.update { it.copy(expenses = expenses.sortedByDescending { e -> e.expenseDate }) }
                    }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, error = null) }
            val summaryResult = repository.summary()
            if (summaryResult is AppResult.Failure) {
                _state.update { current ->
                    current.copy(error = if (current.summary == null) summaryResult.error.message else null)
                }
            } else if (summaryResult is AppResult.Success) {
                _state.update { it.copy(summary = summaryResult.data) }
            }
            val refreshResult = repository.refresh()
            if (refreshResult is AppResult.Failure) {
                _state.update { current ->
                    current.copy(
                        isRefreshing = false,
                        error = if (current.summary == null && current.sales.isEmpty() && current.expenses.isEmpty()) {
                            refreshResult.error.message
                        } else {
                            current.error
                        },
                    )
                }
            } else {
                _state.update { it.copy(isRefreshing = false) }
                // Re-fetch summary after cache refresh for consistency
                repository.summary().onSuccess { data -> _state.update { it.copy(summary = data) } }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null, formError = null) }

    fun onTabChange(index: Int) = _state.update { it.copy(selectedTab = index) }

    fun showAddDialog(show: Boolean, type: AddType = AddType.SALE) {
        _state.update {
            it.copy(
                showAddDialog = show,
                addType = type,
                formError = null,
                isSaving = false,
                // reset sale
                saleItem = "",
                saleQuantity = "",
                saleUnitPrice = "",
                saleAmount = "",
                saleBuyer = "",
                saleDate = todayIso(),
                saleNotes = "",
                // reset expense
                expenseCategory = "",
                expenseAmount = "",
                expenseDescription = "",
                expenseDate = todayIso(),
            )
        }
    }

    fun onSaleItemChange(v: String) = _state.update { it.copy(saleItem = v) }
    fun onSaleQuantityChange(v: String) = _state.update { it.copy(saleQuantity = v) }
    fun onSaleUnitPriceChange(v: String) = _state.update { it.copy(saleUnitPrice = v) }
    fun onSaleAmountChange(v: String) = _state.update { it.copy(saleAmount = v) }
    fun onSaleBuyerChange(v: String) = _state.update { it.copy(saleBuyer = v) }
    fun onSaleDateChange(v: String) = _state.update { it.copy(saleDate = v) }
    fun onSaleNotesChange(v: String) = _state.update { it.copy(saleNotes = v) }

    fun onExpenseCategoryChange(v: String) = _state.update { it.copy(expenseCategory = v) }
    fun onExpenseAmountChange(v: String) = _state.update { it.copy(expenseAmount = v) }
    fun onExpenseDescriptionChange(v: String) = _state.update { it.copy(expenseDescription = v) }
    fun onExpenseDateChange(v: String) = _state.update { it.copy(expenseDate = v) }

    fun save() {
        when (_state.value.addType) {
            AddType.SALE -> saveSale()
            AddType.EXPENSE -> saveExpense()
        }
    }

    private fun saveSale() {
        val s = _state.value
        val qty = s.saleQuantity.toDoubleOrNull()
        val unit = s.saleUnitPrice.toDoubleOrNull()
        val amount = when {
            s.saleAmount.toDoubleOrNull() != null -> s.saleAmount.toDoubleOrNull()
            qty != null && unit != null -> qty * unit
            else -> null
        }
        if (s.saleItem.isBlank()) {
            _state.update { it.copy(formError = "Enter the item sold.") }
            return
        }
        if (amount == null || amount <= 0.0) {
            _state.update { it.copy(formError = "Enter a valid amount.") }
            return
        }
        val farmerId = this.farmerId.value ?: return
        val now = nowIso()
        val id = UUID.randomUUID().toString()
        val txn = SaleTransaction(
            transactionId = id,
            farmerId = farmerId,
            item = s.saleItem.trim(),
            quantity = qty ?: 0.0,
            unitPrice = unit,
            amount = amount,
            buyerName = s.saleBuyer.trim().ifBlank { null },
            date = s.saleDate.ifBlank { todayIso() },
            notes = s.saleNotes.trim().ifBlank { null },
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, formError = null) }
            when (repository.saveSale(txn)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSaving = false, showAddDialog = false) }
                }
                is AppResult.Failure -> _state.update { it.copy(isSaving = false, formError = "Could not save sale.") }
            }
        }
    }

    private fun saveExpense() {
        val s = _state.value
        val amt = s.expenseAmount.toDoubleOrNull()
        if (s.expenseCategory.isBlank()) {
            _state.update { it.copy(formError = "Enter an expense category.") }
            return
        }
        if (amt == null || amt <= 0.0) {
            _state.update { it.copy(formError = "Enter a valid amount.") }
            return
        }
        val farmerId = this.farmerId.value ?: return
        val now = nowIso()
        val id = UUID.randomUUID().toString()
        val exp = Expense(
            expenseId = id,
            farmerId = farmerId,
            category = s.expenseCategory.trim(),
            description = s.expenseDescription.trim().ifBlank { null },
            amount = amt,
            expenseDate = s.expenseDate.ifBlank { todayIso() },
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, formError = null) }
            when (repository.saveExpense(exp)) {
                is AppResult.Success -> _state.update { it.copy(isSaving = false, showAddDialog = false) }
                is AppResult.Failure -> _state.update { it.copy(isSaving = false, formError = "Could not save expense.") }
            }
        }
    }

    private fun todayIso(): String = java.time.LocalDate.now().toString()
}
