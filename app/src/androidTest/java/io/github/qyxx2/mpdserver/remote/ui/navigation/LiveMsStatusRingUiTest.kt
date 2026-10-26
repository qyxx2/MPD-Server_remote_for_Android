package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, read-only LAN smoke test.
 *
 * Run with instrumentation argument:
 *   -e class io.github.qyxx2.mpdserver.remote.ui.navigation.LiveMsStatusRingUiTest
 *   -e liveOrigin http://192.168.3.94:18000
 *
 * Without liveOrigin this test is skipped, so the normal offline test suite remains hermetic.
 * It only reads the health/state/realtime snapshot; it does not submit mutations.
 */
@RunWith(AndroidJUnit4::class)
class LiveMsStatusRingUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun realMsSnapshotAppearsInActivityDetailsWithoutShowingIdleRingOrSubmittingMutations() {
        val originText = InstrumentationRegistry.getArguments().getString("liveOrigin")
        assumeTrue("Supply -e liveOrigin <http(s)://MS-host:port> to run this opt-in live test", !originText.isNullOrBlank())
        val origin = ServerOrigin.parse(requireNotNull(originText))
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val runtime = application.runtime
        val originalOrigin = runtime.realtimeController.currentOrigin()

        try {
            composeRule.setContent {
                val controller = rememberNavController()
                AppNavHost(
                    runtime = runtime,
                    navController = controller,
                    uiPreferences = application.uiPreferences,
                    preferenceWriteScope = application.uiPreferencesWriteScope,
                )
            }
            runBlocking {
                runtime.changeOrigin(origin)
                runtime.start()
                withTimeout(20_000L) {
                    runtime.snapshots.state.first { state ->
                        state.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
                            state.freshness == SnapshotFreshness.FRESH &&
                            state.snapshot != null
                    }
                }
            }

            val liveState = runtime.snapshots.state.value
            assertNotNull("A complete canonical snapshot must be accepted", liveState.snapshot)
            assertTrue("Connected live snapshot must enable status based on the shared runtime", liveState.controlsEligible)
            assertTrue("The server's fresh playback observation should be projected honestly", liveState.actualPlaybackConfirmed)
            assertTrue("The read-only smoke test must not create mutation activities", runtime.mutations.activities.value.isEmpty())

            composeRule.waitUntil(5_000L) {
                runCatching { composeRule.onNodeWithTag("activity-details-entry").fetchSemanticsNode() }.isSuccess
            }
            composeRule.onNodeWithTag("activity-details-entry").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("连接：已连接").assertIsDisplayed()
            composeRule.onNodeWithText("快照：最新").assertIsDisplayed()
            composeRule.onNodeWithTag("activity-status-empty").assertIsDisplayed()
            composeRule.onAllNodesWithTag("status-ring-overlay").assertCountEquals(0)
        } finally {
            runBlocking {
                runtime.realtimeController.stop()
                runtime.changeOrigin(originalOrigin)
            }
        }
    }
}
