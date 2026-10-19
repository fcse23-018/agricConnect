package bw.agriconnect.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Storefront
import bw.agriconnect.domain.model.UserRole
import bw.agriconnect.ui.components.AgriNavItem

/**
 * Role-aware navigation model.
 *
 * Each role lands on its own home and sees only the tabs the backend will actually
 * serve it:
 *  - **Farm owner** owns the land — plots, satellite crop monitoring, selling and
 *    buying.
 *  - **Farmer** works the land — the owner's plots to record against, plus buying.
 *  - **Buyer** buys — the catalogue and order tracking.
 *
 * **Every role gets a Market and a More tab.** Anyone can buy, and More is the only
 * route to the profile, settings and sign-out, so no role may be locked out of it.
 *
 * The backend enforces these boundaries (RBAC middleware); the client mirrors them so a
 * role never sees a tab that would only return `403`.
 */
object AgriNavItems {

    private val home = AgriNavItem(Routes.DASHBOARD, "Home", Icons.Filled.Home)
    private val farms = AgriNavItem(Routes.FARMS, "Farm", Icons.Filled.Agriculture)
    private val finance = AgriNavItem(Routes.FINANCE, "Finance", Icons.Filled.AccountBalanceWallet)
    private val satellite = AgriNavItem(Routes.SATELLITE_TAB, "Satellite", Icons.Filled.Satellite)
    private val market = AgriNavItem(Routes.MARKETPLACE, "Market", Icons.Filled.Storefront)
    private val orders = AgriNavItem(Routes.ORDERS, "Orders", Icons.Filled.ReceiptLong)
    private val alerts = AgriNavItem(Routes.NOTIFICATIONS, "Alerts", Icons.Filled.Notifications)
    private val profile = AgriNavItem(Routes.PROFILE, "Profile", Icons.Filled.Person)
    private val more = AgriNavItem(Routes.MORE, "More", Icons.Filled.MoreHoriz)

    fun forRole(role: UserRole?): List<AgriNavItem> = when (role) {
        // Owns the farm: farm plots plus satellite monitoring of the same plots, and
        // both sides of the marketplace. Finance lives on the home shortcuts.
        UserRole.FARM_OWNER -> listOf(home, farms, satellite, market, more)
        // Works the land: the owner's plots to record against, and buying.
        UserRole.FARMER -> listOf(home, farms, market, orders, more)
        // Buys: the catalogue first, then order tracking.
        UserRole.BUYER -> listOf(market, orders, alerts, more)
        null -> emptyList()
    }

    /** The destination a freshly signed-in user should land on. */
    fun homeRouteFor(role: UserRole?): String = when (role) {
        UserRole.BUYER -> Routes.MARKETPLACE
        else -> Routes.DASHBOARD
    }
}
