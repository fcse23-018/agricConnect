package bw.agriconnect.ui.navigation

/**
 * Type-safe-ish route constants for the Compose navigation graph.
 *
 * The minimal shell wires the three destinations that make the app runnable
 * end-to-end: splash (session restore) → login → dashboard. Feature destinations
 * (farms, finance, satellite, marketplace, orders, notifications, settings) are
 * intentionally **not** registered yet; see PROGRESS.md for the tracked gap.
 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val DASHBOARD = "dashboard"
}
