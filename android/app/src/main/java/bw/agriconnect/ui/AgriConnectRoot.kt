package bw.agriconnect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import bw.agriconnect.domain.model.AppNotification
import bw.agriconnect.domain.model.Session
import bw.agriconnect.ui.auth.ForgotPasswordScreen
import bw.agriconnect.ui.auth.LoginScreen
import bw.agriconnect.ui.auth.RegisterScreen
import bw.agriconnect.ui.chrome.LocalMainChrome
import bw.agriconnect.ui.chrome.MainChrome
import bw.agriconnect.ui.dashboard.DashboardScreen
import bw.agriconnect.ui.farms.FarmDetailScreen
import bw.agriconnect.ui.farms.FarmScreen
import bw.agriconnect.ui.farms.HarvestsScreen
import bw.agriconnect.ui.farms.LivestockScreen
import bw.agriconnect.ui.finance.FinanceScreen
import bw.agriconnect.ui.marketplace.CreateListingScreen
import bw.agriconnect.ui.marketplace.ListingDetailScreen
import bw.agriconnect.ui.marketplace.MarketplaceScreen
import bw.agriconnect.ui.navigation.AgriNavItems
import bw.agriconnect.ui.navigation.Routes
import bw.agriconnect.ui.notifications.NotificationsScreen
import bw.agriconnect.ui.onboarding.OnboardingScreen
import bw.agriconnect.ui.orders.OrderDetailScreen
import bw.agriconnect.ui.orders.OrdersScreen
import bw.agriconnect.ui.profile.ProfileScreen
import bw.agriconnect.ui.satellite.SatelliteScreen
import bw.agriconnect.ui.settings.AccountDeletionScreen
import bw.agriconnect.ui.settings.HelpScreen
import bw.agriconnect.ui.settings.NotificationPrefsScreen
import bw.agriconnect.ui.settings.OfflineSyncScreen
import bw.agriconnect.ui.settings.PrivacyScreen
import bw.agriconnect.ui.settings.ServerSettingsScreen
import bw.agriconnect.ui.settings.SettingsScreen
import bw.agriconnect.ui.splash.SplashScreen
import bw.agriconnect.ui.update.UpdateDialogHost
import bw.agriconnect.ui.welcome.WelcomeScreen
import java.util.Locale

/**
 * The navigation root.
 *
 * Owns the single [rememberNavController] and hands every screen the ambient
 * [MainChrome] (role-aware bottom navigation, the unread bell, and the offline /
 * pending-sync strips) through [LocalMainChrome], so tab screens only describe their
 * own title and content. Top-level tab destinations render the bottom bar; pushed
 * screens (those with a back affordance) do not — see
 * [bw.agriconnect.ui.chrome.MainScaffold].
 *
 * Back-stack hygiene: sign-in lands on the home route for the freshly-resolved role and
 * sign-out clears the entire stack, so the user can never navigate "back" into a screen
 * that belongs to the other auth state.
 */
@Composable
fun AgriConnectRoot(
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val session by rootViewModel.session.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val navItems = remember(session?.role) { AgriNavItems.forRole(session?.role) }
    val homeRoute = AgriNavItems.homeRouteFor(session?.role)
    val hasAlertsTab = navItems.any { it.route == Routes.NOTIFICATIONS }

    val chrome = MainChrome(
        navItems = navItems,
        currentRoute = currentRoute,
        onNavigate = { route ->
            navController.navigate(route) {
                // Keep the role's home destination at the base of the authenticated stack
                // so switching tabs never piles up history.
                popUpTo(homeRoute) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
    )

    /** Clears the whole stack and lands on [route] — used for auth transitions. */
    fun clearTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    CompositionLocalProvider(LocalMainChrome provides chrome) {
        NavHost(navController = navController, startDestination = Routes.SPLASH) {
            composable(Routes.SPLASH) {
                SplashScreen(onResolved = { resolved: Session? ->
                    val target = if (resolved != null) AgriNavItems.homeRouteFor(resolved.role) else Routes.WELCOME
                    clearTo(target)
                })
            }
            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onGetStarted = { navController.navigate(Routes.ONBOARDING) },
                    onSignIn = { navController.navigate(Routes.LOGIN) },
                    onServerSettings = { navController.navigate(Routes.SERVER) },
                )
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = { clearTo(Routes.REGISTER) })
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = { clearTo(AgriNavItems.homeRouteFor(rootViewModel.session.value?.role)) },
                    onForgotPassword = { navController.navigate(Routes.FORGOT_PASSWORD) },
                    onRegister = { navController.navigate(Routes.REGISTER) },
                    onServerSettings = { navController.navigate(Routes.SERVER) },
                    // Back to the main (welcome) screen. After sign-out the stack holds
                    // only LOGIN, so popping fails — fall back to clearing to Welcome.
                    onBack = { if (!navController.popBackStack()) clearTo(Routes.WELCOME) },
                )
            }
            composable(Routes.REGISTER) {
                RegisterScreen(
                    onRegistered = { clearTo(AgriNavItems.homeRouteFor(rootViewModel.session.value?.role)) },
                    onBackToLogin = { navController.popBackStack() },
                    // Sign-up can be reached from onboarding as well as from sign-in, so go
                    // straight to the main screen rather than relying on the back stack.
                    onBackToMain = { clearTo(Routes.WELCOME) },
                )
            }
            composable(Routes.FORGOT_PASSWORD) {
                ForgotPasswordScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { clearTo(Routes.LOGIN) },
                )
            }

            /* ------------------------------ tab destinations ----------------------------- */

            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onLoggedOut = { clearTo(Routes.LOGIN) },
                    onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
                )
            }
            composable(Routes.FARMS) {
                FarmScreen(
                    onPlotClick = { id -> navController.navigate(Routes.farmDetail(id)) },
                    onOpenHarvests = { navController.navigate(Routes.HARVESTS) },
                    onOpenLivestock = { navController.navigate(Routes.LIVESTOCK) },
                )
            }
            composable(Routes.FINANCE) {
                FinanceScreen(onBack = null)
            }
            composable(Routes.SATELLITE_TAB) {
                bw.agriconnect.ui.satellite.SatelliteScreen(
                    plotId = null,
                    onBack = null,
                    onOpenPlot = { id -> if (id.isNotBlank()) navController.navigate(Routes.farmDetail(id)) },
                    onAddPlot = { navController.navigate(Routes.FARMS) },
                )
            }
            composable(Routes.MARKETPLACE) {
                MarketplaceScreen(
                    onListingClick = { id -> navController.navigate(Routes.listingDetail(id)) },
                    onCreateListing = { navController.navigate(Routes.LISTING_NEW) },
                )
            }
            composable(Routes.ORDERS) {
                OrdersScreen(
                    onOrderClick = { id -> navController.navigate(Routes.orderDetail(id)) },
                )
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(
                    onBack = if (hasAlertsTab) null else ({ navController.popBackStack() }),
                    onOpen = { notification -> handleNotificationOpen(navController, notification) },
                )
            }
            composable(Routes.PROFILE) {
                ProfileScreen(
                    onBack = null,
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onNotifications = { navController.navigate(Routes.NOTIFICATION_PREFS) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onDeleteAccount = { navController.navigate(Routes.ACCOUNT_DELETION) },
                    onLogout = { clearTo(Routes.LOGIN) },
                )
            }
            composable(Routes.MORE) {
                ProfileScreen(
                    onBack = null,
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onNotifications = { navController.navigate(Routes.NOTIFICATION_PREFS) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onDeleteAccount = { navController.navigate(Routes.ACCOUNT_DELETION) },
                    onLogout = { clearTo(Routes.LOGIN) },
                )
            }

            /* ---------------------------- pushed destinations ---------------------------- */

            composable(
                Routes.FARM_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_PLOT_ID) { type = NavType.StringType }),
            ) { entry ->
                val plotId = entry.arguments?.getString(Routes.ARG_PLOT_ID) ?: return@composable
                FarmDetailScreen(
                    plotId = plotId,
                    onBack = { navController.popBackStack() },
                    onOpenSatellite = { navController.navigate(Routes.satellite(plotId)) },
                    onDeleted = { navController.popBackStack() },
                )
            }
            composable(Routes.HARVESTS) {
                HarvestsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LIVESTOCK) {
                LivestockScreen(onBack = { navController.popBackStack() })
            }
            composable(
                Routes.SATELLITE,
                arguments = listOf(navArgument(Routes.ARG_PLOT_ID) { type = NavType.StringType }),
            ) { entry ->
                val plotId = entry.arguments?.getString(Routes.ARG_PLOT_ID) ?: return@composable
                SatelliteScreen(
                    plotId = plotId,
                    onBack = { navController.popBackStack() },
                    onOpenPlot = { id -> if (id.isNotBlank()) navController.navigate(Routes.farmDetail(id)) },
                    onAddPlot = { navController.navigate(Routes.FARMS) },
                )
            }
            composable(Routes.LISTING_NEW) {
                CreateListingScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { listingId ->
                        navController.popBackStack()
                        if (listingId.isNotBlank()) navController.navigate(Routes.listingDetail(listingId))
                    },
                )
            }
            composable(
                Routes.LISTING_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_LISTING_ID) { type = NavType.StringType }),
            ) { entry ->
                val listingId = entry.arguments?.getString(Routes.ARG_LISTING_ID) ?: return@composable
                ListingDetailScreen(
                    listingId = listingId,
                    onBack = { navController.popBackStack() },
                    onOrderPlaced = { orderId -> navController.navigate(Routes.orderDetail(orderId)) },
                )
            }
            composable(
                Routes.ORDER_DETAIL,
                arguments = listOf(navArgument(Routes.ARG_ORDER_ID) { type = NavType.StringType }),
            ) { entry ->
                val orderId = entry.arguments?.getString(Routes.ARG_ORDER_ID) ?: return@composable
                OrderDetailScreen(
                    orderId = orderId,
                    onBack = { navController.popBackStack() },
                    onOpenListing = { id -> navController.navigate(Routes.listingDetail(id)) },
                )
            }

            /* --------------------------------- settings --------------------------------- */

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNotifications = { navController.navigate(Routes.NOTIFICATION_PREFS) },
                    onPrivacy = { navController.navigate(Routes.PRIVACY) },
                    onOfflineSync = { navController.navigate(Routes.OFFLINE_SYNC) },
                    onHelp = { navController.navigate(Routes.HELP) },
                    onServer = { navController.navigate(Routes.SERVER) },
                    onDeleteAccount = { navController.navigate(Routes.ACCOUNT_DELETION) },
                    onLogout = { clearTo(Routes.LOGIN) },
                )
            }
            composable(Routes.NOTIFICATION_PREFS) {
                NotificationPrefsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ACCOUNT_DELETION) {
                AccountDeletionScreen(
                    onBack = { navController.popBackStack() },
                    onDeleted = { clearTo(Routes.LOGIN) },
                )
            }
            composable(Routes.PRIVACY) {
                PrivacyScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDelete = { navController.navigate(Routes.ACCOUNT_DELETION) },
                )
            }
            composable(Routes.OFFLINE_SYNC) {
                OfflineSyncScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.HELP) {
                HelpScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SERVER) {
                ServerSettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // Phase C: update dialogs render above navigation so a required update can
    // block any screen, and an optional one can appear wherever the user is.
    // State lives in UpdateViewModel -> UpdateChecker (never in `remember`).
    UpdateDialogHost()
}

/**
 * Routes a notification to the entity it refers to. Unknown types are ignored rather
 * than guessed at, so a future server-side type can never strand the user on a blank
 * screen.
 */
private fun handleNotificationOpen(navController: NavHostController, notification: AppNotification) {
    val id = notification.relatedEntityId ?: return
    val route = when (notification.relatedEntityType?.uppercase(Locale.US)) {
        "ORDER" -> Routes.orderDetail(id)
        "LISTING", "PRODUCE_LISTING" -> Routes.listingDetail(id)
        "FARM_PLOT", "PLOT", "FARM" -> Routes.farmDetail(id)
        else -> return
    }
    navController.navigate(route)
}
