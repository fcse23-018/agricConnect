package bw.agriconnect.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import bw.agriconnect.ui.auth.LoginScreen
import bw.agriconnect.ui.dashboard.DashboardScreen
import bw.agriconnect.ui.navigation.Routes
import bw.agriconnect.ui.splash.SplashScreen

/**
 * The navigation root. Owns a single [rememberNavController] and the splash → login
 * → dashboard flow.
 *
 * Back-stack hygiene: sign-in and sign-out both `popUpTo` (inclusive) so the user
 * cannot navigate "back" into a screen that belongs to the other auth state.
 */
@Composable
fun AgriConnectRoot() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onResolved = { signedIn ->
                    val target = if (signedIn) Routes.DASHBOARD else Routes.LOGIN
                    navController.navigate(target) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onLoggedOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                },
            )
        }
    }
}
