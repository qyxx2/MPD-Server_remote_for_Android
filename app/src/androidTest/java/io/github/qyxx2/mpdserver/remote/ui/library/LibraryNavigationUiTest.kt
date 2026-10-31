package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppDestination
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    private fun launchAppNavigation() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            AppNavHost(
                runtime = application.runtime,
                navController = controller,
                uiPreferences = application.uiPreferences,
                preferenceWriteScope = application.uiPreferencesWriteScope,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun libraryRootRendersAllEightCategoriesInContractOrderWithoutRecommendations() {
        composeRule.setContent {
            LibraryScreen(onOpenCategory = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("library-root-title").assertIsDisplayed()
        composeRule.onNodeWithTag("library-category-list").assertIsDisplayed()

        val expected = listOf("songs", "playlists", "artists", "albums", "genres", "years", "tags", "favorites")
        val tags = expected.map { "library-category-$it" }
        tags.forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }

        val positions = tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Library categories must remain in the contract order", positions.zipWithNext().all { it.first < it.second })
        assertEquals(8, expected.size)
        composeRule.onAllNodesWithTag("library-recently-added").assertCountEquals(0)
        composeRule.onAllNodesWithTag("library-recommendations").assertCountEquals(0)
    }

    @Test
    fun rootCategoryTapReturnsTheExactCategoryWithoutSynthesizingData() {
        var selected: LibraryCategory? = null
        composeRule.setContent {
            LibraryScreen(onOpenCategory = { selected = it })
        }
        composeRule.onNodeWithTag("library-category-albums").performClick()
        composeRule.waitForIdle()

        assertEquals(LibraryCategory.ALBUMS, selected)
    }

    @Test
    fun nestedLibraryCategoryUsesLibraryRootSelectionAndReturnsToLibrary() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val originBefore = runBlocking { application.uiPreferences.serverOrigin.first() }
        launchAppNavigation()

        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").performClick()
        composeRule.onNodeWithTag("library-category-artists").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == LibraryRoutes.CATEGORY_PATTERN
        }
        assertEquals("artists", navController.currentBackStackEntry?.arguments?.getString(LibraryRoutes.KIND_ARGUMENT))
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").assertIsDisplayed()
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").assertIsSelected()

        composeRule.onAllNodesWithTag("library-page-back").assertCountEquals(0)
        composeRule.onNodeWithTag("library-summary-screen-artists").performTouchInput {
            val start = center
            down(start)
            moveTo(Offset(start.x + 52.dp.toPx(), start.y + 26.dp.toPx()), 90L)
            moveTo(Offset(start.x + 118.dp.toPx(), start.y - 10.dp.toPx()), 170L)
            moveTo(Offset(start.x + 190.dp.toPx(), start.y + 34.dp.toPx()), 260L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.LIBRARY.route
        }
        assertEquals(originBefore, runBlocking { application.uiPreferences.serverOrigin.first() })
    }

    @Test
    fun androidSystemBackReturnsFromAnyLibraryChildWithoutVisibleBackText() {
        launchAppNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").performClick()
        composeRule.onNodeWithTag("library-category-favorites").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == LibraryRoutes.CATEGORY_PATTERN &&
                navController.currentBackStackEntry?.arguments?.getString(LibraryRoutes.KIND_ARGUMENT) == "favorites"
        }
        composeRule.onAllNodesWithText("返回").assertCountEquals(0)

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == AppDestination.LIBRARY.route
        }
    }

    @Test
    fun configuredServerReadOnlySongsRequestFeedsTheLibraryScreenWithoutChangingOrigin() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val configuredOrigin = runBlocking { application.uiPreferences.serverOrigin.first() }
        assertTrue("The existing configured MS origin must be reused", configuredOrigin != ServerOrigin.DEFAULT_ADDRESS)

        val resourceIdentity = runBlocking {
            withTimeout(20_000) {
                application.runtime.resources.identityState.first { it.isFresh }
            }
        }
        assertTrue(resourceIdentity.session != null)
        val songsResult = runBlocking { application.runtime.reads.getSongs(sortBy = "title", sortOrder = "asc") }
        assertTrue("MS must return the read-only Library Songs resource: $songsResult", songsResult is ApiResult.Success)
        val songs = (songsResult as ApiResult.Success).value
        assertTrue("The configured library should expose songs for the Songs screen", songs.items.isNotEmpty())

        launchAppNavigation()
        composeRule.onNodeWithTag("root-nav-${AppDestination.LIBRARY.route}").performClick()
        composeRule.onNodeWithTag("library-category-songs").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("library-song-list")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeRule.onNodeWithTag("library-song-list").assertIsDisplayed()
        composeRule.onNodeWithTag("library-song-sort-button").assertIsDisplayed()
        assertEquals(configuredOrigin, runBlocking { application.uiPreferences.serverOrigin.first() })
    }

    @Test
    fun fullScreenPlayerReturnsToTheExactNestedLibraryRoute() {
        launchAppNavigation()
        composeRule.runOnUiThread {
            navController.navigate(AppDestination.LIBRARY.route)
            navController.navigate(LibraryRoutes.category(LibraryCategory.SONGS))
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == LibraryRoutes.CATEGORY_PATTERN &&
                navController.currentBackStackEntry?.arguments?.getString(LibraryRoutes.KIND_ARGUMENT) == "songs"
        }
        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route &&
                composeRule.onAllNodesWithTag("full-screen-player-root")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }

        val swipeDistancePx = with(composeRule.density) { 280.dp.toPx() }
        composeRule.onNodeWithTag("full-screen-exit-gesture-band").performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + swipeDistancePx), 180L)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            navController.currentDestination?.route == LibraryRoutes.CATEGORY_PATTERN &&
                navController.currentBackStackEntry?.arguments?.getString(LibraryRoutes.KIND_ARGUMENT) == "songs"
        }
        assertEquals("songs", navController.currentBackStackEntry?.arguments?.getString(LibraryRoutes.KIND_ARGUMENT))
    }

}
