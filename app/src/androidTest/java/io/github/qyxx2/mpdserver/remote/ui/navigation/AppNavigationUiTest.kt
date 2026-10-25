package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var runtime: AppRuntime

    private fun launchNavigation() {
        runtime = (composeRule.activity.application as MpdServerRemoteApplication).runtime
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            AppNavHost(runtime = runtime, navController = controller)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun rootDestinationsRenderInFrozenOrderAndTrackSelectedRoute() {
        launchNavigation()
        val destinations = AppDestination.rootDestinations
        assertEquals(listOf("主页", "搜索", "音乐库", "设置"), destinations.map { it.label })

        val tags = destinations.map { "root-nav-${it.route}" }
        val leftPositions = tags.map { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.left }
        assertTrue("Root navigation must render in the frozen left-to-right order", leftPositions.zipWithNext().all { it.first < it.second })

        destinations.forEach { destination ->
            val tag = "root-nav-${destination.route}"
            composeRule.onNodeWithText(destination.label).assertIsDisplayed()
            composeRule.onNodeWithTag(tag).performClick()
            composeRule.waitForIdle()
            assertEquals(destination.route, navController.currentDestination?.route)
            composeRule.onNodeWithTag(tag).assertIsSelected()
        }
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)
    }

    @Test
    fun selectingCurrentRootDoesNotReplaceItsBackStackEntryOrRuntimeOwner() {
        launchNavigation()
        val homeTag = "root-nav-${AppDestination.HOME.route}"
        composeRule.onNodeWithTag(homeTag).performClick()
        composeRule.waitForIdle()
        val selectedEntry = navController.currentBackStackEntry

        composeRule.onNodeWithTag(homeTag).performClick()
        composeRule.waitForIdle()

        assertSame(selectedEntry, navController.currentBackStackEntry)
        assertSame(runtime, (composeRule.activity.application as MpdServerRemoteApplication).runtime)
    }

    @Test
    fun fullScreenPlayerHidesRootNavigationAndSystemBackRestoresEachRoot() {
        launchNavigation()
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val originalRuntime = application.runtime

        AppDestination.rootDestinations.forEach { destination ->
            composeRule.onNodeWithTag("root-nav-${destination.route}").performClick()
            composeRule.waitForIdle()
            assertEquals(destination.route, navController.currentDestination?.route)
            val rootEntry = navController.currentBackStackEntry

            composeRule.runOnUiThread {
                navController.navigate(AppDestination.FULL_SCREEN_PLAYER.route)
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("全屏播放器占位").assertIsDisplayed()
            composeRule.onAllNodesWithTag("root-navigation-bar").assertCountEquals(0)

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
            assertEquals(destination.route, navController.currentDestination?.route)
            assertSame(rootEntry, navController.currentBackStackEntry)
            composeRule.onNodeWithTag("root-nav-${destination.route}").assertIsSelected()
            assertSame(originalRuntime, application.runtime)
        }
    }
}
