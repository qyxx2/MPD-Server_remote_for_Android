package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdDatabaseUpdateStatusDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdStatsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdStatusDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ServerSettingsMpdStatusUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val application: MpdServerRemoteApplication
        get() = composeRule.activity.application as MpdServerRemoteApplication
    private var originalOrigin: ServerOrigin? = null
    private var originalAddress: String? = null

    private fun status(
        version: String? = "0.23.5",
        connected: Boolean? = true,
        errors: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    ) = MpdStatusDto(
        version = version,
        stats = MpdStatsDto(
            artists = 18, albums = 42, songs = 407,
            uptime = 183845.9, dbPlaytime = 987654.75, dbUpdate = 1793520000,
            playtime = 54321.5,
        ),
        databaseUpdateStatus = MpdDatabaseUpdateStatusDto(updating = false, jobId = null),
        connected = connected,
        errors = errors,
        observedAt = "2026-11-01T06:30:00Z",
    )

    @Before
    fun captureOriginalOrigin() {
        runBlocking {
            originalAddress = application.uiPreferences.serverOrigin.first()
            originalOrigin = ServerOrigin.parseAddress(requireNotNull(originalAddress))
        }
    }

    @After
    fun restoreOriginalOrigin() {
        val origin = originalOrigin ?: return
        runBlocking { application.saveAndApplyServerOrigin(origin) }
    }

    private fun render(reader: suspend () -> ApiResult<MpdStatusDto>) {
        composeRule.setContent {
            ServerSettingsScreen(
                preferencesRepository = application.uiPreferences,
                onApplyServerOrigin = application::saveAndApplyServerOrigin,
                readMpdStatus = reader,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun serverPageLoadsMpdStatusOnEntryAndRefreshesOnDemand() {
        val reads = AtomicInteger()
        render {
            reads.incrementAndGet()
            ApiResult.Success(status(), 200)
        }

        composeRule.waitUntil(5_000) {
            reads.get() == 1 && composeRule.onAllNodesWithText("0.23.5").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, reads.get())
        composeRule.onNodeWithTag("mpd-status-refresh").assertIsDisplayed().performClick()
        composeRule.waitUntil(5_000) { reads.get() == 2 }
        composeRule.waitForIdle()
        assertEquals(2, reads.get())
        composeRule.onNodeWithTag("mpd-status-connection").assertIsDisplayed().assertTextEquals("已连接")
    }

    @Test
    fun serverPageMarksSameOriginReadFailureStaleAndClearsOldOriginStatus() {
        val reads = AtomicInteger()
        render {
            when (reads.incrementAndGet()) {
                1 -> ApiResult.Success(status(version = "OLD-MPD"), 200)
                2 -> ApiResult.ProtocolError("same-origin refresh failed")
                else -> ApiResult.Success(status(version = "NEW-MPD"), 200)
            }
        }

        composeRule.waitUntil(5_000) {
            reads.get() == 1 && composeRule.onAllNodesWithText("OLD-MPD").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("mpd-status-refresh").performClick()
        composeRule.waitUntil(5_000) {
            reads.get() == 2 && composeRule.onAllNodesWithText("数据已过期").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("OLD-MPD").assertIsDisplayed()
        composeRule.onNodeWithText("same-origin refresh failed").assertIsDisplayed()

        val changedOrigin = ServerOrigin.parseAddress("http://status-new-origin.example:18000")
        runBlocking { application.saveAndApplyServerOrigin(changedOrigin) }
        composeRule.waitUntil(5_000) {
            reads.get() == 3 && composeRule.onAllNodesWithText("NEW-MPD").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("NEW-MPD").assertIsDisplayed()
        composeRule.onAllNodesWithText("OLD-MPD").assertCountEquals(0)
    }


    @Test
    fun configuredServerProvidesLiveMpdStatusThroughTheExistingRuntime() {
        val savedOrigin = runBlocking { application.uiPreferences.serverOrigin.first() }
        assertEquals(savedOrigin, application.runtime.realtimeController.currentOrigin().address)

        val result = runBlocking { application.runtime.reads.getMpdStatus() }
        assertTrue("GET /api/system/mpd must decode successfully from the configured server: $result", result is ApiResult.Success)
        val success = result as ApiResult.Success<MpdStatusDto>
        assertEquals(200, success.statusCode)
        val dto = success.value
        android.util.Log.i(
            "Task6Batch2",
            "GET /api/system/mpd HTTP 200; versionPresent=${dto.version != null}; " +
                "mpdConnected=${dto.connected}; statsPresent=${dto.stats != null}; " +
                "errorKeys=${dto.errors?.keys?.sorted()}",
        )
    }

    @Test
    fun serverPageDisplaysDisconnectedAndMpdErrorsWithoutFalseSuccess() {
        render {
            ApiResult.Success(
                MpdStatusDto(
                    stats = MpdStatsDto(),
                    databaseUpdateStatus = MpdDatabaseUpdateStatusDto(updating = true, jobId = JsonPrimitive(72)),
                    connected = false,
                    errors = mapOf("mpd" to JsonPrimitive("read timeout")),
                ),
                200,
            )
        }

        composeRule.onNodeWithTag("mpd-status-connection").assertTextEquals("未连接")
        composeRule.onNodeWithText("正在更新").assertIsDisplayed()
        composeRule.onNodeWithText("mpd: \"read timeout\"").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("未知").fetchSemanticsNodes().isNotEmpty())
    }
}
