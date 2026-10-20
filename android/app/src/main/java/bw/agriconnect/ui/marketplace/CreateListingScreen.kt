package bw.agriconnect.ui.marketplace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.ImageFiles
import bw.agriconnect.domain.model.Unit as MeasureUnit
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriFilterChip
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriRemoteImage
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing
import kotlinx.coroutines.launch

/**
 * Create-and-publish a produce listing. Listings are created and published over REST
 * (the sync protocol has no listing payload), so this form is honest about needing a
 * connection — a failure is shown, never faked as success.
 */
@Composable
fun CreateListingScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: CreateListingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

    MainScaffold(title = "New listing", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            InfoBanner(
                message = "Buyers see a listing as soon as it is published. You can update the price or archive it later.",
            )
            AgriTextField(
                value = state.produce,
                onValueChange = viewModel::onProduceChange,
                label = "Produce",
            )
            AgriTextField(
                value = state.description,
                onValueChange = viewModel::onDescriptionChange,
                label = "Description",
            )
            AgriTextField(
                value = state.quantity,
                onValueChange = viewModel::onQuantityChange,
                label = if (state.isLivestock) "Number of ${state.unit.label}" else "Quantity",
                keyboardType = KeyboardType.Decimal,
            )

            // A picker, not a text box: the unit must be one the server accepts, so
            // the user chooses from the real list instead of typing "cattle" and
            // hitting a 400 on publish. Produce and livestock are separated because
            // they answer different questions — "how much" versus "how many".
            UnitPicker(
                selected = state.unit,
                onSelect = viewModel::onUnitChange,
                enabled = !state.isBusy,
            )

            AgriTextField(
                value = state.price,
                onValueChange = viewModel::onPriceChange,
                label = if (state.isLivestock) "Price per ${state.unit.label} (P)" else "Price per unit (P)",
                keyboardType = KeyboardType.Decimal,
            )
            AgriTextField(
                value = state.locationText,
                onValueChange = viewModel::onLocationChange,
                label = "Location",
            )
            Text(
                text = "Photo (optional)",
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            )
            state.imagePreview?.let { preview ->
                AgriRemoteImage(
                    url = preview,
                    contentDescription = "Selected listing photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(AgriRadius.md)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                AgriSecondaryButton(
                    text = if (state.imagePreview == null) "Add photo" else "Change photo",
                    onClick = { pickImage.launch("image/*") },
                    enabled = !state.isBusy,
                    fillWidth = false,
                )
                if (state.imagePreview != null) {
                    AgriSecondaryButton(
                        text = "Remove",
                        onClick = viewModel::onImageCleared,
                        enabled = !state.isBusy,
                        fillWidth = false,
                    )
                }
            }
            if (state.error != null) ErrorBanner(message = state.error.orEmpty())
            AgriPrimaryButton(
                text = "Publish listing",
                onClick = { viewModel.save(onSaved) },
                loading = state.isBusy,
            )
            AgriSecondaryButton(
                text = "Cancel",
                onClick = onBack,
                enabled = !state.isSaving,
            )
            Text(
                text = "The produce name, quantity and price are required.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Chooses the listing unit from the values the server accepts.
 *
 * Grouped Produce / Livestock. "Head" is one animal (20 head), while "herd" and
 * "flock" sell the whole group as a single lot (quantity 1, one price) — the two
 * ways farmers actually sell animals. Because the choice is a fixed list, a listing
 * can never be created with a unit the server will reject.
 */
@Composable
private fun UnitPicker(
    selected: MeasureUnit,
    onSelect: (MeasureUnit) -> Unit,
    enabled: Boolean,
) {
    val produce = MeasureUnit.entries.filterNot { it.livestock }
    val livestock = MeasureUnit.entries.filter { it.livestock }

    Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
        Text(
            text = "Unit",
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
        )
        UnitGroup(
            title = "Produce",
            units = produce,
            selected = selected,
            onSelect = onSelect,
            enabled = enabled,
        )
        UnitGroup(
            title = "Livestock",
            units = livestock,
            selected = selected,
            onSelect = onSelect,
            enabled = enabled,
        )
        Text(
            text = "\"head\" is one animal (e.g. 20 head). \"herd\" or \"flock\" sells the whole group as one lot — set quantity to 1.",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One labelled row of unit chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitGroup(
    title: String,
    units: List<MeasureUnit>,
    selected: MeasureUnit,
    onSelect: (MeasureUnit) -> Unit,
    enabled: Boolean,
) {
    Text(
        text = title,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AgriSpacing.xs),
    ) {
        units.forEach { unit ->
            AgriFilterChip(
                label = unit.label,
                selected = unit == selected,
                onClick = { if (enabled) onSelect(unit) },
            )
        }
    }
}
