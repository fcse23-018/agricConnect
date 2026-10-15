package bw.agriconnect.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.core.util.MoneyFormat
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriBarChart
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriSegmentedControl
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.InlineLoading
import bw.agriconnect.ui.components.PulaAmount
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.components.StatTile
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun FinanceScreen(
    onBack: (() -> Unit)? = null,
    viewModel: FinanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MainScaffold(
        title = "Finance",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog(true, AddType.SALE) },
                containerColor = AgriColors.ForestGreen,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add record", tint = AgriColors.PureWhite)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            if (state.isRefreshing) {
                InlineLoading(message = "Refreshing finance…")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AgriSpacing.lg)
                    .padding(top = AgriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            ) {
                AgriCard {
                    Column {
                        Text("Net balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(AgriSpacing.xs))
                        Text(
                            MoneyFormat.pula(state.netBalance),
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (state.netBalance >= 0.0) AgriColors.SuccessGreen else AgriColors.ErrorRed,
                        )
                        Text("Includes cached sales and expenses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    StatTile(
                        label = "Total sales",
                        value = MoneyFormat.pula(state.totalSales),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Total expenses",
                        value = MoneyFormat.pula(state.totalExpenses),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (state.points.isNotEmpty()) {
                    AgriCard {
                        Column {
                            SectionHeader(title = "Sales vs Expenses")
                            Spacer(Modifier.height(AgriSpacing.md))
                            AgriBarChart(points = state.points)
                        }
                    }
                }

                AgriSegmentedControl(
                    options = listOf("Overview", "Sales", "Expenses"),
                    selectedIndex = state.selectedTab,
                    onSelect = viewModel::onTabChange,
                )
            }

            when (state.selectedTab) {
                0 -> OverviewTab(state.sales, state.expenses)
                1 -> ListTab(
                    items = state.sales.map { FinanceListItem(label = it.item, amount = it.amount, date = it.date, isPositive = true, buyer = it.buyerName) },
                    emptyTitle = "No sales",
                    emptyMessage = "Record sales when produce is sold.",
                )
                2 -> ListTab(
                    items = state.expenses.map { FinanceListItem(label = it.category, amount = it.amount, date = it.expenseDate, isPositive = false, buyer = it.description) },
                    emptyTitle = "No expenses",
                    emptyMessage = "Track inputs and costs.",
                )
                else -> Unit
            }
        }
    }

    if (state.showAddDialog) {
        AddRecordDialog(state, viewModel)
    }
}

private data class FinanceListItem(
    val label: String,
    val amount: Double,
    val date: String,
    val isPositive: Boolean,
    val buyer: String? = null,
)

@Composable
private fun OverviewTab(sales: List<bw.agriconnect.domain.model.SaleTransaction>, expenses: List<bw.agriconnect.domain.model.Expense>) {
    if (sales.isEmpty() && expenses.isEmpty()) {
        EmptyState(title = "No finance data", message = "Add a sale or expense to see a summary.")
        return
    }
    // Keep minimal
    ListTab(
        items = buildList {
            addAll(sales.map { FinanceListItem(label = it.item, amount = it.amount, date = it.date, isPositive = true) })
            addAll(expenses.map { FinanceListItem(label = it.category, amount = it.amount, date = it.expenseDate, isPositive = false) })
        }.sortedByDescending { it.date },
        emptyTitle = "Nothing here",
        emptyMessage = "",
    )
}

@Composable
private fun ListTab(items: List<FinanceListItem>, emptyTitle: String, emptyMessage: String) {
    if (items.isEmpty()) {
        EmptyState(title = emptyTitle, message = emptyMessage)
        return
    }
    LazyColumn(
        modifier = Modifier.padding(horizontal = AgriSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = AgriSpacing.xl),
    ) {
        items(items, key = { "${it.label}-${it.date}-${it.amount}" }) { item ->
            AgriCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.titleSmall)
                        item.buyer?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(DateFormatters.formatDate(item.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        (if (item.isPositive) "+" else "-") + MoneyFormat.pula(item.amount),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.isPositive) AgriColors.SuccessGreen else AgriColors.ErrorRed,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddRecordDialog(state: FinanceUiState, viewModel: FinanceViewModel) {
    AlertDialog(
        onDismissRequest = { if (!state.isSaving) viewModel.showAddDialog(false) },
        title = { Text(if (state.addType == AddType.SALE) "Add sale" else "Add expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                AgriSegmentedControl(
                    options = listOf("Sale", "Expense"),
                    selectedIndex = if (state.addType == AddType.SALE) 0 else 1,
                    onSelect = { idx -> viewModel.showAddDialog(true, if (idx == 0) AddType.SALE else AddType.EXPENSE) },
                )
                if (state.addType == AddType.SALE) {
                    AgriTextField(value = state.saleItem, onValueChange = viewModel::onSaleItemChange, label = "Item")
                    AgriTextField(value = state.saleQuantity, onValueChange = viewModel::onSaleQuantityChange, label = "Quantity", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.saleUnitPrice, onValueChange = viewModel::onSaleUnitPriceChange, label = "Unit price", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.saleAmount, onValueChange = viewModel::onSaleAmountChange, label = "Amount (optional)", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.saleBuyer, onValueChange = viewModel::onSaleBuyerChange, label = "Buyer (optional)")
                    AgriTextField(value = state.saleDate, onValueChange = viewModel::onSaleDateChange, label = "Date (YYYY-MM-DD)")
                } else {
                    AgriTextField(value = state.expenseCategory, onValueChange = viewModel::onExpenseCategoryChange, label = "Category")
                    AgriTextField(value = state.expenseAmount, onValueChange = viewModel::onExpenseAmountChange, label = "Amount", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.expenseDescription, onValueChange = viewModel::onExpenseDescriptionChange, label = "Description (optional)")
                    AgriTextField(value = state.expenseDate, onValueChange = viewModel::onExpenseDateChange, label = "Date (YYYY-MM-DD)")
                }
                if (state.formError != null) {
                    Text(state.formError.orEmpty(), color = AgriColors.ErrorRed)
                }
            }
        },
        confirmButton = {
            AgriPrimaryButton(text = "Save", onClick = viewModel::save, loading = state.isSaving, fillWidth = false)
        },
        dismissButton = {
            AgriSecondaryButton(text = "Cancel", onClick = { viewModel.showAddDialog(false) }, enabled = !state.isSaving, fillWidth = false)
        },
    )
}
