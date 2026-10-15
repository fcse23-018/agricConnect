package bw.agriconnect.ui.farms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.ConfirmationDialog
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.LoadingState
import bw.agriconnect.ui.components.NdviGauge
import bw.agriconnect.ui.components.SectionHeader
import bw.agriconnect.ui.components.SyncStatusBadge
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing
import java.text.NumberFormat

@Composable
fun FarmDetailScreen(
    plotId: String,
    onBack: () -> Unit,
    onOpenSatellite: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: FarmDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val harvests by viewModel.harvests.collectAsStateWithLifecycle()

    LaunchedEffect(plotId) { viewModel.setPlotId(plotId) }

    MainScaffold(
        title = state.plot?.plotName ?: "Plot details",
        onBack = onBack,
        topBarActions = {
            if (state.plot != null) {
                androidx.compose.material3.IconButton(onClick = { viewModel.showEdit(true) }) {
                    androidx.compose.material3.Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
                androidx.compose.material3.IconButton(onClick = { viewModel.showDelete(true) }) {
                    androidx.compose.material3.Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = AgriColors.ErrorRed)
                }
            }
        },
    ) { padding ->
        if (state.isLoading && state.plot == null) {
            LoadingState(message = "Loading plot…")
            return@MainScaffold
        }
        if (state.error != null && state.plot == null) {
            EmptyState(title = "Could not load plot", message = state.error.orEmpty(), actionLabel = "Try again", onAction = { viewModel.refresh() })
            return@MainScaffold
        }
        val plot = state.plot
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            plot?.let {
                AgriCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.plotName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            SyncStatusBadge(status = it.syncStatus)
                        }
                        it.areaHectares?.let { ha ->
                            Text("${NumberFormat.getInstance().format(ha)} ha", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (it.centroidLat != null && it.centroidLng != null) {
                            Text("Location: ${it.centroidLat}, ${it.centroidLng}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        it.notes?.takeIf { n -> n.isNotBlank() }?.let { n ->
                            Spacer(Modifier.height(AgriSpacing.xs))
                            Text(n, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            AgriCard(onClick = onOpenSatellite) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    androidx.compose.material3.Icon(Icons.Filled.Map, contentDescription = null, tint = AgriColors.ForestGreen)
                    Column(Modifier.weight(1f)) {
                        Text("Satellite insights", style = MaterialTheme.typography.titleSmall)
                        Text("View NDVI crop health for this plot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Icon(Icons.Filled.Map, contentDescription = null)
                }
            }

            state.ndvi?.let { ndvi ->
                NdviGauge(meanNdvi = ndvi.meanNdviValue, statusText = "Latest acquisition", cached = ndvi.isCached)
            }

            SectionHeader(title = "Harvests on this plot")
            if (harvests.isEmpty()) {
                EmptyState(title = "No harvests", message = "Record a harvest from the Harvests screen.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    harvests.forEach { h ->
                        AgriCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.cropType, style = MaterialTheme.typography.titleSmall)
                                    Text("${h.quantityKg} kg • ${h.harvestDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SyncStatusBadge(status = h.syncStatus)
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showEdit) {
        AlertDialog(
            onDismissRequest = { if (!state.isSaving) viewModel.showEdit(false) },
            title = { Text("Edit plot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    AgriTextField(value = state.name, onValueChange = viewModel::onNameChange, label = "Plot name")
                    AgriTextField(value = state.area, onValueChange = viewModel::onAreaChange, label = "Area (ha)", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.lat, onValueChange = viewModel::onLatChange, label = "Latitude", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.lng, onValueChange = viewModel::onLngChange, label = "Longitude", keyboardType = KeyboardType.Number)
                    AgriTextField(value = state.notes, onValueChange = viewModel::onNotesChange, label = "Notes")
                    if (state.formError != null) Text(state.formError.orEmpty(), color = AgriColors.ErrorRed)
                }
            },
            confirmButton = { AgriPrimaryButton(text = "Save", onClick = { viewModel.save({}) }, loading = state.isSaving, fillWidth = false) },
            dismissButton = { AgriSecondaryButton(text = "Cancel", onClick = { viewModel.showEdit(false) }, enabled = !state.isSaving, fillWidth = false) },
        )
    }

    ConfirmationDialog(
        visible = state.showDelete,
        title = "Delete plot",
        message = "This will remove the plot. Harvests and satellite data remain associated for reporting, but the plot may no longer be visible in your list if your device removes it. Continue?",
        confirmLabel = "Delete",
        onConfirm = { viewModel.delete(onDeleted) },
        onDismiss = { viewModel.showDelete(false) },
        destructive = true,
    )
}
