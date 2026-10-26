package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import io.github.qyxx2.mpdserver.remote.ui.shell.AppShell
import io.github.qyxx2.mpdserver.remote.ui.shell.RootNavigationBar

/**
 * Navigation only: the runtime is supplied by MainActivity from the Application owner.
 * This UI shell observes no runtime state and does not start or replace the runtime.
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
    val fullScreen = currentRoute == AppDestination.FULL_SCREEN_PLAYER.route
    val title = AppDestination.rootDestinations
        .firstOrNull { it.route == currentRoute }
        ?.label
        ?: AppDestination.HOME.label

    if (fullScreen) {
        AppNavigationGraph(
            navController = navController,
            modifier = modifier.fillMaxSize(),
        )
    } else {
        AppShell(
            title = title,
            modifier = modifier,
            bottomBar = {
                RootNavigationBar(
                    currentRoute = currentRoute,
                    navController = navController,
                )
            },
        ) {
            AppNavigationGraph(
                navController = navController,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AppNavigationGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AppDestination.HOME.route,
        modifier = modifier,
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
}
