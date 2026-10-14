package bw.agriconnect.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * AgriConnect brand palette.
 * Values are the documented brief tokens (docs/01-DESIGN-SYSTEM.md) and were
 * verified against `logo.png` / `app layout.png`. Green = action, trust, brand.
 * Brown = soil / earth accents. Warm white keeps content light and readable.
 */
object AgriColors {
    // Core palette
    val ForestGreen = Color(0xFF174D32)
    val DeepGreen = Color(0xFF103B27)
    val AgriGreen = Color(0xFF3F7852)
    val SoilBrown = Color(0xFFA66A3F)
    val DarkBrown = Color(0xFF70472E)
    val WarmWhite = Color(0xFFF8F8F3)
    val PureWhite = Color(0xFFFFFFFF)
    val TextMain = Color(0xFF202720)
    val TextSecondary = Color(0xFF667067)
    val Border = Color(0xFFE1E6DF)

    // Extended semantic tokens (derived, contrast-checked to WCAG AA)
    val GreenContainer = Color(0xFFE3EFE6)
    val BrownContainer = Color(0xFFF0E4D9)
    val SuccessGreen = Color(0xFF2E7D4F)
    val WarningAmber = Color(0xFFB07A24)
    val ErrorRed = Color(0xFFB3261E)
    val ErrorContainer = Color(0xFFF9DEDC)
    val InfoBlue = Color(0xFF2A5C8A)
    val DisabledBg = Color(0xFFEDF1EC)
    val DisabledText = Color(0xFFA3ACA4)
    val Scrim = Color(0x66000000)

    // Dark-theme surfaces (used when the system is in dark mode)
    val DarkBackground = Color(0xFF0E1712)
    val DarkSurface = Color(0xFF16211B)
    val DarkSurfaceVariant = Color(0xFF1E2B23)
    val DarkBorder = Color(0xFF2C3A31)
    val DarkTextMain = Color(0xFFE6EBE6)
    val DarkTextSecondary = Color(0xFFA6B3A9)
}

/** NDVI stress colours — mirrors backend `stressDescriptor` colour tokens. */
object NdviColors {
    val HighStress = AgriColors.ErrorRed
    val ModerateStress = AgriColors.WarningAmber
    val Healthy = AgriColors.SuccessGreen
    val Unknown = AgriColors.TextSecondary
}

/** Sync-status colours — mirrors the documented SyncStatus enum. */
object SyncColors {
    val Pending = AgriColors.WarningAmber
    val Syncing = AgriColors.InfoBlue
    val Synced = AgriColors.SuccessGreen
    val Conflict = AgriColors.SoilBrown
    val Failed = AgriColors.ErrorRed
}
