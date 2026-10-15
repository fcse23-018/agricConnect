package bw.agriconnect.ui.chrome

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.components.AgriNavItem
import bw.agriconnect.ui.components.AgriScaffold

/**
 * Ambient main-navigation state supplied by the root graph.
 *
 * Rather than thread `navItems` / `currentRoute` / `onNavigate` through the signature of
 * every tab screen, the root provides this once and [MainScaffold] reads it. A pushed
 * screen (one with a back affordance) simply does not render the bottom bar, so the same
 * wrapper serves both cases.
 */
data class MainChrome(
    val navItems: List<AgriNavItem>,
    val currentRoute: String?,
    val onNavigate: (String) -> Unit,
    val onOpenNotifications: () -> Unit,
)

/** Null outside the authenticated graph (e.g. splash / auth screens). */
val LocalMainChrome = compositionLocalOf<MainChrome?> { null }

/**
 * The chrome every main screen shares: the standard app bar, the offline / pending-sync
 * strips, the unread bell, and the role-aware bottom navigation.
 *
 * Chrome state (connectivity, outbox depth, unread count) comes from [ChromeViewModel] so
 * a screen only has to describe its own title and content. The bottom bar is shown only
 * for top-level tab destinations — that is, when [onBack] is null.
 */
@Composable
fun MainScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBrand: Boolean = false,
    onBack: (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
    chromeViewModel: ChromeViewModel = hiltViewModel(),
    content: @Composable (PaddingValues) -> Unit,
) {
    val chrome = LocalMainChrome.current
    val isOnline by chromeViewModel.isOnline.collectAsStateWithLifecycle()
    val pending by chromeViewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val unread by chromeViewModel.unreadNotifications.collectAsStateWithLifecycle()

    val showNavigation = onBack == null && chrome != null && chrome.navItems.isNotEmpty()

    AgriScaffold(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        showBrand = showBrand,
        onBack = onBack,
        offline = !isOnline,
        pendingSyncCount = if (isOnline) pending else 0,
        unreadNotifications = unread,
        onNotificationsClick = if (showNavigation) chrome?.onOpenNotifications else null,
        navItems = if (showNavigation) chrome?.navItems.orEmpty() else emptyList(),
        currentRoute = chrome?.currentRoute,
        onNavigate = { route -> chrome?.onNavigate?.invoke(route) },
        floatingActionButton = floatingActionButton,
        topBarActions = topBarActions,
        content = content,
    )
}
