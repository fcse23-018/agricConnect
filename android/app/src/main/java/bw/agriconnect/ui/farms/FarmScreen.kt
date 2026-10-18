package bw.agriconnect.ui.farms

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.ImageFiles
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriRemoteImage
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriSettingsRow
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.EmptyState as AgriEmptyState
import bw.agriconnect.ui.components.SyncStatusBadge
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Farm management: the list of plots plus shortcuts into the record books (harvests,
 * livestock). Plots are read from the offline cache; adding a plot writes locally and
 * enqueues a sync, so it works with no signal.
 */
@Composable
fun FarmScreen(
    onPlotClick: (String) -> Unit,
    onOpenHarvests: () -> Unit,
    onOpenLivestock: () -> Unit,
    viewModel: FarmViewModel = hiltViewModel(),
) {
    val plots by viewModel.plots.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            ImageFiles.read(context, uri)
                .onSuccess { viewModel.onImagePicked(uri.toString(), it) }
                .onFailure { viewModel.onImageError(it.message ?: "Could not read the selected image.") }
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    MainScaffold(
        title = "Farms",
        onBack = null,
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showForm(true) }, containerColor = AgriColors.ForestGreen) {
                Icon(Icons.Filled.Add, contentDescription = "Add plot", tint = AgriColors.PureWhite)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AgriSpacing.lg),
        ) {
            item {
                AgriCard {
                    Column {
                        AgriSettingsRow(title = "Harvest records", subtitle = "Crops collected per plot", icon = Icons.Filled.Grass, onClick = onOpenHarvests)
                        AgriSettingsRow(title = "Livestock", subtitle = "Head counts over time", icon = Icons.Filled.Pets, onClick = onOpenLivestock)
                    }
                }
            }

            if (plots.isEmpty()) {
                item {
                    AgriEmptyState(
                        title = "No plots yet",
                        message = "Add a plot to start tracking crops and satellite vegetation health.",
                        actionLabel = "Add plot",
                        onAction = { viewModel.showForm(true) },
                    )
                }
            } else {
                items(plots, key = { it.plotId }) { plot ->
                    AgriCard(onClick = { onPlotClick(plot.plotId) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                            AgriRemoteImage(
                                url = plot.imageUrl,
                                contentDescription = plot.plotName,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(AgriRadius.md)),
                            )
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        plot.plotName,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    SyncStatusBadge(status = plot.syncStatus)
                                }
                                Spacer(Modifier.height(AgriSpacing.xs))
                                plot.areaHectares?.let {
                                    Text(
                                        "${NumberFormat.getInstance().format(it)} ha",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (plot.centroidLat != null && plot.centroidLng != null) {
                                    Text(
                                        "Location: ${plot.centroidLat}, ${plot.centroidLng}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                plot.notes?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (form.showForm) {
        AlertDialog(
            onDismissRequest = { viewModel.showForm(false) },
            title = { Text("Add plot") },
            text = {
                Column {
                    AgriTextField(value = form.name, onValueChange = viewModel::onNameChange, label = "Plot name")
                    Spacer(Modifier.height(AgriSpacing.sm))
                    AgriTextField(value = form.area, onValueChange = viewModel::onAreaChange, label = "Area (ha)", keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(AgriSpacing.sm))
                    AgriTextField(value = form.lat, onValueChange = viewModel::onLatChange, label = "Latitude (optional)", keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(AgriSpacing.sm))
                    AgriTextField(value = form.lng, onValueChange = viewModel::onLngChange, label = "Longitude (optional)", keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(AgriSpacing.sm))
                    AgriTextField(value = form.notes, onValueChange = viewModel::onNotesChange, label = "Notes (optional)")
                    Spacer(Modifier.height(AgriSpacing.md))
                    Text("Photo (optional)", style = MaterialTheme.typography.titleSmall)
                    form.imagePreview?.let { preview ->
                        Spacer(Modifier.height(AgriSpacing.sm))
                        AgriRemoteImage(
                            url = preview,
                            contentDescription = "Selected plot photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(AgriRadius.md)),
                        )
                    }
                    Spacer(Modifier.height(AgriSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                        AgriSecondaryButton(
                            text = if (form.imagePreview == null) "Add photo" else "Change photo",
                            onClick = { pickImage.launch("image/*") },
                            enabled = !form.isBusy,
                            fillWidth = false,
                        )
                        if (form.imagePreview != null) {
                            AgriSecondaryButton(
                                text = "Remove",
                                onClick = viewModel::onImageCleared,
                                enabled = !form.isBusy,
                                fillWidth = false,
                            )
                        }
                    }
                    if (form.error != null) {
                        Spacer(Modifier.height(AgriSpacing.sm))
                        Text(form.error.orEmpty(), color = AgriColors.ErrorRed)
                    }
                }
            },
            confirmButton = {
                AgriPrimaryButton(
                    text = "Save",
                    onClick = viewModel::savePlot,
                    loading = form.isBusy,
                    fillWidth = false,
                )
            },
            dismissButton = {
                AgriSecondaryButton(text = "Cancel", onClick = { viewModel.showForm(false) }, fillWidth = false)
            },
        )
    }
}
