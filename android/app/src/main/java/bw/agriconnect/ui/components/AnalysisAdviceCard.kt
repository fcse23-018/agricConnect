package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * One analysis, in language a farmer can act on.
 *
 * Every analysis on the farm screen shows: what the reading means, the concrete
 * steps to take, and a caution where the data cannot support a conclusion. The
 * wording is built from the server's own fields (or from the shared, deliberately
 * non-diagnostic recommendation builder) - the device never invents a cause.
 */
@Composable
fun AnalysisAdviceCard(
    title: String,
    reading: String,
    actions: List<String>,
    caution: String? = null,
    modifier: Modifier = Modifier,
) {
    AgriCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AgriColors.GreenContainer, CircleShape),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = AgriColors.ForestGreen,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(AgriSpacing.sm))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AgriColors.ForestGreen,
                    modifier = Modifier.weight(1f),
                )
            }

            if (reading.isNotBlank()) {
                Spacer(Modifier.height(AgriSpacing.sm))
                Text(reading, style = MaterialTheme.typography.bodyMedium)
            }

            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(AgriSpacing.md))
                Text(
                    "What to do",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AgriSpacing.xs))
                actions.forEachIndexed { index, action ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AgriColors.ForestGreen,
                        )
                        Spacer(Modifier.width(AgriSpacing.sm))
                        Text(
                            action,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (index != actions.lastIndex) Spacer(Modifier.height(AgriSpacing.xs))
                }
            }

            if (!caution.isNullOrBlank()) {
                Spacer(Modifier.height(AgriSpacing.md))
                Text(
                    caution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
