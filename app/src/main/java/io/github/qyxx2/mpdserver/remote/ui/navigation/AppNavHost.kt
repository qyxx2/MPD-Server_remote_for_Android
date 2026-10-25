package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.screens.FullScreenPlayerPlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.HomePlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.LibraryPlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.SearchPlaceholderScreen
import io.github.qyxx2.mpdserver.remote.ui.screens.SettingsPlaceholderScreen

/**
 * Navigation only: the runtime is supplied by MainActivity from the Application owner.
 * This batch deliberately does not start, replace, or otherwise operate on that owner.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppNavHost(
    runtime: AppRuntime,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME.route,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            composable(AppDestination.HOME.route) { HomePlaceholderScreen() }
            composable(AppDestination.SEARCH.route) { SearchPlaceholderScreen() }
            composable(AppDestination.LIBRARY.route) { LibraryPlaceholderScreen() }
            composable(AppDestination.SETTINGS.route) { SettingsPlaceholderScreen() }
            composable(AppDestination.FULL_SCREEN_PLAYER.route) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FullScreenPlayerPlaceholderScreen(onBack = { navController.popBackStack() })
                }
            }
        }

        if (currentRoute != AppDestination.FULL_SCREEN_PLAYER.route) {
            RootNavigationBar(
                currentRoute = currentRoute,
                navController = navController,
            )
        }
    }
}

@Composable
private fun RootNavigationBar(
    currentRoute: String?,
    navController: NavHostController,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth().testTag("root-navigation-bar"),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AppDestination.rootDestinations.forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                modifier = Modifier.testTag("root-nav-${destination.route}"),
                selected = selected,
                onClick = {
                    // Selecting the already-selected root is an intentional no-op. It preserves
                    // the current entry and any state held by that root's destination.
                    if (currentRoute != destination.route) {
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
                        AppDestination.FULL_SCREEN_PLAYER -> Unit
                    }
                },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
            )
        }
    }
}
