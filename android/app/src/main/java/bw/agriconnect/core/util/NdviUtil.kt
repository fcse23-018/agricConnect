package bw.agriconnect.core.util

import bw.agriconnect.domain.model.NdviProcessingStatus
import bw.agriconnect.domain.model.StressLevel
import bw.agriconnect.ui.theme.NdviColors
import androidx.compose.ui.graphics.Color

/**
 * NDVI classification, mirroring the backend exactly (docs/02-API-CONTRACT.md).
 * The thresholds are the documented ones and MUST stay in sync with
 * `backend/src/services/ndvi.js`.
 */
object NdviUtil {

    const val HIGH_STRESS_MAX = 0.20
    const val MODERATE_STRESS_MAX = 0.45

    fun classify(mean: Double?): StressLevel? {
        if (mean == null) return null
        return when {
            mean < HIGH_STRESS_MAX -> StressLevel.HIGH_STRESS
            mean < MODERATE_STRESS_MAX -> StressLevel.MODERATE_STRESS
            else -> StressLevel.HEALTHY_VEGETATION
        }
    }

    fun label(level: StressLevel?): String = when (level) {
        StressLevel.HIGH_STRESS -> "High stress"
        StressLevel.MODERATE_STRESS -> "Moderate stress"
        StressLevel.HEALTHY_VEGETATION -> "Healthy vegetation"
        null -> "Not available"
    }

    fun colour(level: StressLevel?): Color = when (level) {
        StressLevel.HIGH_STRESS -> NdviColors.HighStress
        StressLevel.MODERATE_STRESS -> NdviColors.ModerateStress
        StressLevel.HEALTHY_VEGETATION -> NdviColors.Healthy
        null -> NdviColors.Unknown
    }

    /** Guardrail copy: NDVI is an indication, never a diagnosis. */
    const val DISCLAIMER =
        "Satellite vegetation index is an indicative signal, not an agronomic diagnosis. " +
            "Confirm with a field inspection before acting."

    fun isProcessing(status: NdviProcessingStatus): Boolean = status in setOf(
        NdviProcessingStatus.REQUESTED,
        NdviProcessingStatus.FETCHING_IMAGERY,
        NdviProcessingStatus.COMPUTING_NDVI,
    )

    fun failureCopy(reason: String?): String = reason
        ?: "Satellite imagery was not available for this observation. We will try again on the next clear pass."
}
