package io.github.qyxx2.mpdserver.remote.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppDestination

@Composable
fun RootNavigationBar(
    currentRoute: String?,
    navController: NavHostController,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onBoundsChanged: (topPx: Float, heightPx: Float) -> Unit = { _, _ -> },
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .onGloballyPositioned { coordinates ->
                onBoundsChanged(
                    coordinates.positionInRoot().y,
                    coordinates.size.height.toFloat(),
                )
            }
            .testTag("root-navigation-bar"),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AppDestination.rootDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            val selectedContentColor = MaterialTheme.colorScheme.primary
            val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            NavigationBarItem(
                modifier = Modifier.testTag("root-nav-${destination.route}"),
                selected = selected,
                enabled = interactionEnabled,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = selectedContentColor,
                    selectedTextColor = selectedContentColor,
                    unselectedIconColor = unselectedContentColor,
                    unselectedTextColor = unselectedContentColor,
                    // Material 3's default disabled colors use 38% alpha. Transition copies
                    // must stay non-interactive without dimming the navigation content.
                    disabledIconColor = if (selected) selectedContentColor else unselectedContentColor,
                    disabledTextColor = if (selected) selectedContentColor else unselectedContentColor,
                    indicatorColor = Color.Transparent,
                ),
                onClick = {
                    if (interactionEnabled && currentRoute != destination.route) {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    when (destination) {
                        AppDestination.HOME -> Icon(Icons.Filled.Home, contentDescription = null)
                        AppDestination.SEARCH -> Icon(Icons.Filled.Search, contentDescription = null)
                        AppDestination.LIBRARY -> Text(text = "♫", fontSize = 24.sp)
                        AppDestination.SETTINGS -> Icon(Icons.Filled.Settings, contentDescription = null)
                        AppDestination.FULL_SCREEN_PLAYER, AppDestination.QUEUE -> Unit
                    }
                },
                label = { Text(destination.label, fontSize = 12.sp) },
                alwaysShowLabel = true,
            )
        }
    }
}
