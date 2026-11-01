package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryCategory
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryRoutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device, read-only navigation integration. It uses the already configured Application
 * runtime and never clears app data, changes the server address, or submits a server mutation.
 */
@RunWith(AndroidJUnit4::class)
class Task5CrossScreenIntegrationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var application: MpdServerRemoteApplication
    private lateinit var runtime: AppRuntime
    private lateinit var navController: NavHostController
    private lateinit var savedOriginAddress: String
    private lateinit var savedTheme: ThemePreference
    private lateinit var savedRingPreferences: StatusRingPreferences

    @Before
    fun captureExistingAppConfiguration() {
        application = composeRule.activity.application as MpdServerRemoteApplication
        runBlocking {
            savedOriginAddress = application.uiPreferences.serverOrigin.first()
            savedTheme = application.uiPreferences.themePreference.first()
            savedRingPreferences = application.uiPreferences.statusRingPreferences.first()
        }
        runtime = application.runtime
    }

    @After
    fun assertExistingAppConfigurationWasPreserved() {
        runBlocking {
            assertEquals(savedOriginAddress, application.uiPreferences.serverOrigin.first())
            assertEquals(savedTheme, application.uiPreferences.themePreference.first())
            assertEquals(savedRingPreferences, application.uiPreferences.statusRingPreferences.first())
            assertEquals(savedOriginAddress, application.runtime.realtimeController.currentOrigin().address)
        }
        assertSame(runtime, application.runtime)
    }

    @Test
    fun configuredMsFeedsAllTask5RootsAndNestedBackNavigationKeepsOneRuntime() {
        val configuredOrigin = ServerOrigin.parseAddress(savedOriginAddress)
        assertTrue("The already configured MS origin must not fall back to the default", configuredOrigin.address != ServerOrigin.DEFAULT_ADDRESS)

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            AppNavHost(
                runtime = runtime,
                navController = controller,
                uiPreferences = application.uiPreferences,
                preferenceWriteScope = application.uiPreferencesWriteScope,
                applyServerOrigin = application::saveAndApplyServerOrigin,
            )
        }
        composeRule.waitForIdle()

        // Await an accepted live snapshot before evaluating any cache freshness/UI write gate.
        val identity = runBlocking {
            withTimeout(25_000) {
                runtime.resources.identityState.first { it.isFresh && it.session != null }
            }
        }
        assertEquals(savedOriginAddress, identity.session?.originAddress)

        // These are real MS GETs only; no playback, queue, library scan, playlist or favorites writes.
        val recent = runBlocking { runtime.reads.getSongs(sortBy = "modified_at", sortOrder = "desc") }
        val history = runBlocking { runtime.reads.getHistory() }
        val albums = runBlocking { runtime.reads.getAlbums() }
        assertTrue("Configured MS Recent Updates must be readable: $recent", recent is io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success)
        assertTrue("Persistent History must be readable from /api/history: $history", history is io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success)
        assertTrue("Album summaries must use the existing server API: $albums", albums is io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success)

        // Home: three independent rails remain mounted; the shared mini-player is visible.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("home-screen")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        listOf(
            "home-section-recently-updated",
            "home-section-playback-history",
            "home-section-autoplay",
            "home-rail-recently-updated",
            "home-rail-playback-history",
            "home-rail-autoplay",
        ).forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        assertEquals(AppDestination.HOME.route, navController.currentDestination?.route)

        // Search -> nested Library route -> system Back returns to Search, not a new root instance.
        composeRule.onNodeWithTag("root-nav-${AppDestination.SEARCH.route}").performClick()
        composeRule.onNodeWithTag("search-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.runOnUiThread {
            navController.navigate(LibraryRoutes.category(LibraryCategory.SONGS))
        }
        composeRule.waitUntil(10_000) {
            navController.currentDestination?.route == LibraryRoutes.CATEGORY_PATTERN
        }
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").assertIsSelected()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(10_000) {
            navController.currentDestination?.route == AppDestination.SEARCH.route
        }
        composeRule.onNodeWithTag("root-nav-${AppDestination.SEARCH.route}").assertIsSelected()

        // Library nested route keeps Library selected and returns to the same root on Back.
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").performClick()
        composeRule.onNodeWithTag("library-category-songs").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()
        composeRule.onNodeWithTag("library-category-songs").performClick()
        composeRule.waitUntil(10_000) {
            navController.currentDestination?.route == LibraryRoutes.CATEGORY_PATTERN
        }
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").assertIsSelected()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(10_000) {
            navController.currentDestination?.route == AppDestination.LIBRARY.route
        }
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").assertIsSelected()

        // Settings is the only root that deliberately hides the mini-player.
        composeRule.onNodeWithTag("root-nav-${AppDestination.SETTINGS.route}").performClick()
        composeRule.onNodeWithTag("status-ring-settings-content").assertIsDisplayed()
        composeRule.onAllNodesWithTag("mini-player").assertCountEquals(0)
        composeRule.onNodeWithTag("root-nav-${AppDestination.HOME.route}").performClick()
        composeRule.onNodeWithTag("home-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player").assertIsDisplayed()

        // Returning to Home after other roots preserves the canonical Runtime owners.
        assertSame(runtime, application.runtime)
        assertSame(runtime.snapshots, application.runtime.snapshots)
        assertSame(runtime.resources, application.runtime.resources)
        assertSame(runtime.realtimeController, application.runtime.realtimeController)
        assertEquals(savedOriginAddress, runtime.realtimeController.currentOrigin().address)
    }
}
