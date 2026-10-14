package bw.agriconnect.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii from the design system. Restrained and consistent — cards are
 * 20dp, controls 14dp, chips 6dp, bottom sheets 28dp on the top corners.
 */
object AgriRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
    val pill = 999.dp
}

val AgriShapes = Shapes(
    extraSmall = RoundedCornerShape(AgriRadius.xs),
    small = RoundedCornerShape(AgriRadius.sm),
    medium = RoundedCornerShape(AgriRadius.md),
    large = RoundedCornerShape(AgriRadius.lg),
    extraLarge = RoundedCornerShape(AgriRadius.xl),
)

/**
 * 8dp spacing scale. Every layout gap uses one of these tokens so rhythm is
 * consistent across all screens.
 */
object AgriSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 40.dp
    val huge = 48.dp
    val touchTarget = 48.dp
}

/** Restrained elevation tokens. Only FABs and dialogs lift noticeably. */
object AgriElevation {
    val flat = 0.dp
    val card = 1.dp
    val raised = 3.dp
    val fab = 6.dp
    val dialog = 8.dp
}
