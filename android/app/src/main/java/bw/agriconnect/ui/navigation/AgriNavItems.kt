package bw.agriconnect.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Storefront
import bw.agriconnect.domain.model.UserRole
import bw.agriconnect.ui.components.AgriNavItem

/**
 * Role-aware navigation model.
 *
 * The three roles get materially different apps:
 *  - **Farmer** manages plots, finances and their own listings.
 *  - **Farm employee** records field work and reads alerts; finance and account
 *    settings are the owner's.
 *  - **Buyer** browses the marketplace and tracks orders.
 *
 * The backend enforces these boundaries (RBAC middleware); the client mirrors them so a
 * role never sees a tab that would only return `403`.
 */
object AgriNavItems {

    private val home = AgriNavItem(Routes.DASHBOARD, "Home", Icons.Filled.Home)
    private val farms = AgriNavItem(Routes.FARMS, "Farms", Icons.Filled.Agriculture)
    private val finance = AgriNavItem(Routes.FINANCE, "Finance", Icons.Filled.AccountBalanceWallet)
    private val market = AgriNavItem(Routes.MARKETPLACE, "Market", Icons.Filled.Storefront)
    private val orders = AgriNavItem(Routes.ORDERS, "Orders", Icons.Filled.ReceiptLong)
    private val alerts = AgriNavItem(Routes.NOTIFICATIONS, "Alerts", Icons.Filled.Notifications)
    private val profile = AgriNavItem(Routes.PROFILE, "Profile", Icons.Filled.Person)

    fun forRole(role: UserRole?): List<AgriNavItem> = when (role) {
        UserRole.FARMER -> listOf(home, farms, finance, market, profile)
        UserRole.FARM_EMPLOYEE -> listOf(home, farms, alerts, profile)
        UserRole.BUYER -> listOf(market, orders, alerts, profile)
        null -> emptyList()
    }

    /** The destination a freshly signed-in user should land on. */
    fun homeRouteFor(role: UserRole?): String = when (role) {
        UserRole.BUYER -> Routes.MARKETPLACE
        else -> Routes.DASHBOARD
    }
}
