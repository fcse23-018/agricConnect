package bw.agriconnect.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bw.agriconnect.core.util.DateFormatters
import bw.agriconnect.domain.model.NdviAction
import bw.agriconnect.domain.model.NdviAdvice
import bw.agriconnect.domain.model.NdviAdviceContext
import bw.agriconnect.domain.model.NdviAdvicePriority
import bw.agriconnect.domain.model.NdviConfidence
import bw.agriconnect.domain.model.NdviScaleRow
import bw.agriconnect.domain.model.NdviTrend
import bw.agriconnect.domain.model.NdviTrendDirection
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriRadius
import bw.agriconnect.ui.theme.AgriSpacing
import java.util.Locale

/**
 * The advice attached to a vegetation-index reading.
 *
 * Everything rendered here comes from the server (`backend/src/services/ndviAdvice.js`):
 * the device neither classifies the reading nor invents an action. The card is deliberately
 * "one screen of paper" — what the number means, what to do, what to watch for, how far to
 * trust it, and what it cannot say — because that is the difference between a dashboard
 * number and something a farmer can use in the field.
 *
 * Simulation data is called out in the reading's own context line, never buried: the
 * guidance is generic, and pretending a placeholder value was measured would be a lie.
 */
@Composable
fun NdviAdviceCard(advice: NdviAdvice, modifier: Modifier = Modifier) {
    AgriCard(modifier = modifier) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "What to do next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AdvicePriorityPill(advice.priority)
            }

            Spacer(Modifier.height(AgriSpacing.sm))
            Text(
                text = advice.headline,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (advice.summary.isNotBlank()) {
                Spacer(Modifier.height(AgriSpacing.xs))
                Text(
                    text = advice.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (advice.meaning.isNotBlank()) {
                Spacer(Modifier.height(AgriSpacing.md))
                Text(advice.meaning, style = MaterialTheme.typography.bodyMedium)
            }

            advice.trend?.let {
                Spacer(Modifier.height(AgriSpacing.md))
                TrendLine(it)
            }
            advice.context?.let {
                Spacer(Modifier.height(AgriSpacing.sm))
                ReadingContext(it)
            }

            if (advice.actions.isNotEmpty()) {
                Spacer(Modifier.height(AgriSpacing.lg))
                SubHeading("Do this")
                advice.actions.forEachIndexed { index, action -> ActionStep(index + 1, action) }
            }

            if (advice.watchFor.isNotEmpty()) {
                Spacer(Modifier.height(AgriSpacing.lg))
                SubHeading("Watch for in the field")
                advice.watchFor.forEach { BulletLine(it) }
            }

            if (advice.scaleGuide.isNotEmpty()) {
                Spacer(Modifier.height(AgriSpacing.lg))
                SubHeading("How to read the number")
                advice.scaleGuide.forEach { ScaleRowLine(it) }
            }

            if (advice.limits.isNotEmpty()) {
                Spacer(Modifier.height(AgriSpacing.lg))
                SubHeading("What this cannot tell you")
                advice.limits.forEach { BulletLine(it) }
            }

            advice.confidence?.let {
                Spacer(Modifier.height(AgriSpacing.lg))
                ConfidenceBlock(it)
            }

            if (!advice.disclaimer.isNullOrBlank()) {
                Spacer(Modifier.height(AgriSpacing.md))
                Text(
                    text = advice.disclaimer.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Urgency pill. Colour is never the only signal — the wording carries the meaning too. */
@Composable
private fun AdvicePriorityPill(priority: NdviAdvicePriority, modifier: Modifier = Modifier) {
    val colour = when (priority) {
        NdviAdvicePriority.NOW -> AgriColors.ErrorRed
        NdviAdvicePriority.SOON -> AgriColors.WarningAmber
        NdviAdvicePriority.ROUTINE -> AgriColors.SuccessGreen
    }
    Box(
        modifier = modifier
            .background(colour.copy(alpha = 0.14f), RoundedCornerShape(AgriRadius.pill))
            .padding(horizontal = AgriSpacing.md, vertical = AgriSpacing.xs),
    ) {
        Text(priority.displayName, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(AgriSpacing.sm))
}

/** Direction of travel versus the plot's previous reading, stated in words as well as an icon. */
@Composable
private fun TrendLine(trend: NdviTrend) {
    val visual: Triple<ImageVector, Color, String> = when (trend.direction) {
        NdviTrendDirection.IMPROVING ->
            Triple(Icons.AutoMirrored.Filled.TrendingUp, AgriColors.SuccessGreen, "Improving")
        NdviTrendDirection.DECLINING ->
            Triple(Icons.AutoMirrored.Filled.TrendingDown, AgriColors.ErrorRed, "Declining")
        NdviTrendDirection.STABLE ->
            Triple(Icons.AutoMirrored.Filled.TrendingFlat, MaterialTheme.colorScheme.onSurfaceVariant, "Stable")
        NdviTrendDirection.FIRST_READING ->
            Triple(Icons.Filled.Flag, MaterialTheme.colorScheme.onSurfaceVariant, "First reading")
    }
    val (icon, colour, label) = visual

    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(AgriSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colour,
                fontWeight = FontWeight.SemiBold,
            )
            val previous = trend.previousValue
            if (previous != null) {
                Text(
                    text = "Previous " + String.format(Locale.US, "%.2f", previous) +
                        (trend.previousDate?.let { " on ${DateFormatters.formatDate(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!trend.note.isNullOrBlank()) {
                Text(
                    text = trend.note.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the average covers can differ from what was mapped, so the area is stated, not implied. */
@Composable
private fun ReadingContext(context: NdviAdviceContext) {
    val facts = buildList {
        context.areaHectares?.let { add("Area averaged: ${String.format(Locale.US, "%.2f", it)} ha") }
        context.observedOn?.let { add("Observed: ${DateFormatters.formatDate(it)}") }
        context.daysSinceObservation?.let {
            add(if (it == 0) "Today's observation" else "$it day${if (it == 1) "" else "s"} old")
        }
        context.cloudCoverPercent?.let { add("Cloud cover: ${String.format(Locale.US, "%.0f", it)}%") }
        context.source?.let { add("Source: $it") }
    }
    if (facts.isEmpty() && !context.sourceIsSimulated) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        facts.forEach {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (context.sourceIsSimulated) {
            Spacer(Modifier.height(AgriSpacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = AgriColors.WarningAmber,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(AgriSpacing.sm))
                Text(
                    text = "Simulated reading — no satellite imagery was used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AgriColors.WarningAmber,
                )
            }
        }
    }
}

@Composable
private fun ActionStep(number: Int, action: NdviAction) {
    Row(modifier = Modifier.padding(bottom = AgriSpacing.md)) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(AgriColors.GreenContainer, RoundedCornerShape(AgriRadius.pill)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = AgriColors.DeepGreen,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(AgriSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(action.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (action.detail.isNotBlank()) {
                Text(
                    text = action.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(bottom = AgriSpacing.sm)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(AgriSpacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScaleRowLine(row: NdviScaleRow) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = AgriSpacing.sm)) {
        Text(
            text = row.range,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(AgriSpacing.sm))
        Text(
            text = row.meaning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * How far the *measurement* can be trusted — said plainly, because a farmer acting on a
 * hazy average wastes money. The level is shown as a colour and the note explains it.
 */
@Composable
private fun ConfidenceBlock(confidence: NdviConfidence) {
    val colour = when (confidence.level.uppercase(Locale.US)) {
        "HIGH" -> AgriColors.SuccessGreen
        "LOW" -> AgriColors.WarningAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (confidence.level.equals("HIGH", ignoreCase = true)) {
                Icons.Filled.CheckCircle
            } else {
                Icons.Filled.WarningAmber
            },
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(AgriSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Confidence: ${confidence.level.lowercase(Locale.US).replaceFirstChar { it.uppercase() }}",
                style = MaterialTheme.typography.labelLarge,
                color = colour,
                fontWeight = FontWeight.SemiBold,
            )
            if (confidence.note.isNotBlank()) {
                Text(
                    text = confidence.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
