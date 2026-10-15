package bw.agriconnect.ui.navigation

/**
 * Type-safe-ish route constants for the Compose navigation graph.
 *
 * Grouped by feature. Destinations are created eagerly in the NavHost; each keeps
 * its own ViewModel, so only the visited screens pay for their state.
 */
object Routes {
    // Auth / onboarding
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val FORGOT_PASSWORD = "forgot_password"

    // Core bottom-nav destinations (available per role — see [AgriNavItems])
    const val DASHBOARD = "dashboard"
    const val FARMS = "farms"
    const val FINANCE = "finance"
    const val MARKETPLACE = "marketplace"
    const val ORDERS = "orders"
    const val NOTIFICATIONS = "notifications"
    const val PROFILE = "profile"

    // Pushed feature destinations
    const val FARM_DETAIL = "farms/{plotId}"
    const val HARVESTS = "harvests"
    const val LIVESTOCK = "livestock"
    const val SATELLITE = "satellite/{plotId}"
    const val LISTING_NEW = "listing/new"
    const val LISTING_DETAIL = "listing/{listingId}"
    const val ORDER_DETAIL = "order/{orderId}"

    // Settings
    const val SETTINGS = "settings"
    const val NOTIFICATION_PREFS = "settings/notifications"
    const val PRIVACY = "settings/privacy"
    const val OFFLINE_SYNC = "settings/sync"
    const val HELP = "settings/help"
    const val ACCOUNT_DELETION = "settings/delete-account"

    // Navigation argument keys
    const val ARG_PLOT_ID = "plotId"
    const val ARG_LISTING_ID = "listingId"
    const val ARG_ORDER_ID = "orderId"

    fun farmDetail(plotId: String) = "farms/$plotId"
    fun satellite(plotId: String) = "satellite/$plotId"
    fun listingDetail(listingId: String) = "listing/$listingId"
    fun orderDetail(orderId: String) = "order/$orderId"
}
