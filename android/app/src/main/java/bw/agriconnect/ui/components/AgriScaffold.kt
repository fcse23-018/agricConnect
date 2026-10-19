package bw.agriconnect.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bw.agriconnect.ui.theme.AgriColors

/**
 * Standard top app bar: an optional brand mark, back affordance, and a
 * notification bell carrying an unread badge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgriTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBrand: Boolean = false,
    onBack: (() -> Unit)? = null,
    unreadNotifications: Int = 0,
    onNotificationsClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            if (showBrand) {
                BrandLogo(markSize = 32.dp, showWordmark = true)
            } else {
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            actions()
            if (onNotificationsClick != null) {
                IconButton(onClick = onNotificationsClick) {
                    BadgedBox(
                        badge = {
                            if (unreadNotifications > 0) {
                                Badge { Text(if (unreadNotifications > 99) "99+" else "$unreadNotifications") }
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** A bottom-navigation destination. */
data class AgriNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun AgriBottomBar(
    items: List<AgriNavItem>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = {
                    // Two lines with an ellipsis fallback: on a narrow phone five
                    // destinations leave a small column each, and a label that is
                    // clipped off the bottom of the bar is a label nobody can read.
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        softWrap = true,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AgriColors.ForestGreen,
                    selectedTextColor = AgriColors.ForestGreen,
                    indicatorColor = AgriColors.GreenContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * Screen scaffold that guarantees the offline strip, the pending-sync strip and
 * the role-aware bottom navigation are present on every main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgriScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBrand: Boolean = false,
    onBack: (() -> Unit)? = null,
    offline: Boolean = false,
    pendingSyncCount: Int = 0,
    unreadNotifications: Int = 0,
    onNotificationsClick: (() -> Unit)? = null,
    navItems: List<AgriNavItem> = emptyList(),
    currentRoute: String? = null,
    onNavigate: (String) -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AgriTopAppBar(
                title = title,
                subtitle = subtitle,
                showBrand = showBrand,
                onBack = onBack,
                unreadNotifications = unreadNotifications,
                onNotificationsClick = onNotificationsClick,
                actions = topBarActions,
            )
        },
        bottomBar = {
            if (navItems.isNotEmpty()) {
                AgriBottomBar(items = navItems, currentRoute = currentRoute, onNavigate = onNavigate)
            }
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(visible = offline, pendingCount = pendingSyncCount)
            if (!offline) PendingSyncBanner(pendingCount = pendingSyncCount)
            content(PaddingValues(bottom = 0.dp))
        }
    }
}
