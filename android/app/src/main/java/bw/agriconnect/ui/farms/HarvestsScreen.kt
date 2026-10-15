package bw.agriconnect.ui.farms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriFilterChip
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.SyncStatusBadge
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Harvest records. Entries are saved locally first and queued for sync, so this screen
 * works offline; the record's [bw.agriconnect.domain.model.SyncStatus] is always visible
 * so the user can tell what has and has not reached the server.
 */
@Composable
fun HarvestsScreen(
    onBack: () -> Unit,
    viewModel: HarvestsViewModel = hiltViewModel(),
) {
    val harvests by viewModel.harvests.collectAsStateWithLifecycle()
    val plots by viewModel.plots.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }

    var crop by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var plotId by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    MainScaffold(
        title = "Harvest records",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { showForm = true }, containerColor = AgriColors.ForestGreen) {
                Icon(Icons.Filled.Add, contentDescription = "Add harvest", tint = AgriColors.PureWhite)
            }
        },
    ) { padding ->
        if (harvests.isEmpty()) {
            EmptyState(
                title = "No harvests yet",
                message = "Add a harvest to track your crops. Harvests you add offline are saved on this device and sync later.",
                actionLabel = "Add harvest",
                onAction = { showForm = true },
            )
            return@MainScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AgriSpacing.lg),
        ) {
            items(harvests, key = { it.harvestId }) { h ->
                AgriCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(h.cropType, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${h.quantityKg} kg • ${h.harvestDate}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val plotName = plots.firstOrNull { it.plotId == h.plotId }?.plotName
                            if (plotName != null) {
                                Text(
                                    "Plot: $plotName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        SyncStatusBadge(status = h.syncStatus)
                    }
                }
            }
        }
    }

    if (showForm) {
        val quantityValue = quantity.toDoubleOrNull()
        val isValid = crop.isNotBlank() && quantityValue != null && quantityValue > 0.0

        AlertDialog(
            onDismissRequest = {
                if (saveError == null) {
                    showForm = false
                    viewModel.clearSaveError()
                }
            },
            title = { Text("Add harvest") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    AgriTextField(value = crop, onValueChange = { crop = it }, label = "Crop type")
                    AgriTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = "Quantity (kg)",
                        keyboardType = KeyboardType.Decimal,
                    )
                    AgriTextField(value = date, onValueChange = { date = it }, label = "Harvest date (YYYY-MM-DD)")
                    AgriTextField(value = notes, onValueChange = { notes = it }, label = "Notes (optional)")

                    Text("Plot (optional)", style = MaterialTheme.typography.labelMedium)
                    if (plots.isEmpty()) {
                        Text(
                            "No plots yet — add one under Farms to link harvests to a plot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs)) {
                            AgriFilterChip(
                                label = "None",
                                selected = plotId.isBlank(),
                                onClick = { plotId = "" },
                            )
                            plots.take(3).forEach { plot ->
                                AgriFilterChip(
                                    label = plot.plotName,
                                    selected = plotId == plot.plotId,
                                    onClick = { plotId = plot.plotId },
                                )
                            }
                        }
                    }

                    if (saveError != null) {
                        ErrorBanner(message = saveError.orEmpty())
                    } else if (quantity.isNotBlank() && !isValid) {
                        Text(
                            "Enter a crop name and a quantity greater than zero.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                AgriPrimaryButton(
                    text = "Save",
                    onClick = {
                        viewModel.saveHarvest(crop, quantityValue ?: 0.0, date, plotId, notes) {
                            crop = ""
                            quantity = ""
                            date = java.time.LocalDate.now().toString()
                            plotId = ""
                            notes = ""
                            showForm = false
                        }
                    },
                    enabled = isValid,
                    fillWidth = false,
                )
            },
            dismissButton = {
                AgriSecondaryButton(
                    text = "Cancel",
                    onClick = {
                        showForm = false
                        viewModel.clearSaveError()
                    },
                    fillWidth = false,
                )
            },
        )
    }
}
