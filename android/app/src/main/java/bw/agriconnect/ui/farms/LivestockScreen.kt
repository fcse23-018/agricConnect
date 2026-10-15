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
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.EmptyState
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.SyncStatusBadge
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Livestock head counts. Saved locally first and queued for sync (offline-first), with
 * the sync state of every record shown so nothing is silently stuck on the device.
 */
@Composable
fun LivestockScreen(
    onBack: () -> Unit,
    viewModel: LivestockViewModel = hiltViewModel(),
) {
    val livestock by viewModel.livestock.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }
    var animal by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    var notes by remember { mutableStateOf("") }

    MainScaffold(
        title = "Livestock",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { showForm = true }, containerColor = AgriColors.ForestGreen) {
                Icon(Icons.Filled.Add, contentDescription = "Add", tint = AgriColors.PureWhite)
            }
        },
    ) { padding ->
        if (livestock.isEmpty()) {
            EmptyState(
                title = "No livestock records",
                message = "Add head counts as you record them. Records saved offline sync automatically later.",
                actionLabel = "Add",
                onAction = { showForm = true },
            )
            return@MainScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = AgriSpacing.lg),
        ) {
            items(livestock, key = { it.countId }) { c ->
                AgriCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.animalType} • ${c.headCount} head", style = MaterialTheme.typography.titleSmall)
                            Text(
                                c.recordDate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SyncStatusBadge(status = c.syncStatus)
                    }
                }
            }
        }
    }

    if (showForm) {
        val countValue = count.toIntOrNull()
        val isValid = animal.isNotBlank() && countValue != null && countValue > 0

        AlertDialog(
            onDismissRequest = {
                if (saveError == null) {
                    showForm = false
                    viewModel.clearSaveError()
                }
            },
            title = { Text("Add livestock") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    AgriTextField(value = animal, onValueChange = { animal = it }, label = "Animal type")
                    AgriTextField(
                        value = count,
                        onValueChange = { count = it },
                        label = "Head count",
                        keyboardType = KeyboardType.Number,
                    )
                    AgriTextField(value = date, onValueChange = { date = it }, label = "Record date (YYYY-MM-DD)")
                    AgriTextField(value = notes, onValueChange = { notes = it }, label = "Notes (optional)")

                    if (saveError != null) {
                        ErrorBanner(message = saveError.orEmpty())
                    } else if (count.isNotBlank() && !isValid) {
                        Text(
                            "Enter an animal type and a head count greater than zero.",
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
                        viewModel.save(animal, countValue ?: 0, date, notes) {
                            animal = ""
                            count = ""
                            date = java.time.LocalDate.now().toString()
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
