package io.github.qyxx2.mpdserver.remote.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotSource
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Cross-screen regression using an isolated in-memory canonical snapshot. The runtime origin is
 * loopback-only; this test never mutates the user's configured MS server or its Queue.
 */
class Task4CrossScreenIntegrationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun rootPlayerLyricsArtworkQueueMenuAndBackKeepOneCanonicalRuntimeAndOccurrenceIdentity() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val runtime = AppRuntime.create(
            initialOrigin = ServerOrigin.parse("http://127.0.0.1:1"),
            httpClient = OkHttpClient.Builder()
                .callTimeout(1, TimeUnit.SECONDS)
                .build(),
        )
        try {
            runtime.snapshots.onConnectionState(0, RuntimeConnectionStatus.CONNECTED)
            assertTrue(runtime.snapshots.accept(snapshotDto(), 0, SnapshotSource.REALTIME_INITIAL))
            val canonicalStore = runtime.snapshots
            val canonicalSnapshot = requireNotNull(canonicalStore.state.value.snapshot)
            val canonicalPlayer = PlayerPresentation.from(canonicalStore.state.value)
            assertEquals(
                "canonical player projection must bind the song to current occurrence: $canonicalPlayer",
                "Task 4 integration title",
                canonicalPlayer.song?.title,
            )
            assertTrue(canonicalStore.state.value.controlsEligible)

            composeRule.setContent {
                MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                    val controller = rememberNavController()
                    SideEffect { navController = controller }
                    AppNavHost(
                        runtime = runtime,
                        navController = controller,
                        uiPreferences = application.uiPreferences,
                        preferenceWriteScope = rememberCoroutineScope(),
                    )
                }
            }

            composeRule.onNodeWithTag("mini-player-body").assertIsDisplayed()
            composeRule.onNodeWithTag("mini-player-body").performClick()
            val minimumTouchPx = with(composeRule.density) { 48.dp.toPx() }
            composeRule.waitUntil(10_000) {
                val playerVisible = composeRule.onAllNodesWithTag("full-screen-player-root")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
                val outputNode = composeRule.onAllNodesWithTag("player-bottom-output")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()
                navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route &&
                    playerVisible && outputNode != null && outputNode.boundsInRoot.height >= minimumTouchPx
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
            composeRule.onNodeWithTag("player-song-title").assertExists()
            composeRule.onNodeWithText("Task 4 integration title").assertExists()
            composeRule.onNodeWithTag("player-bottom-now-playing").assertIsDisplayed()
            assertSame(canonicalStore, runtime.snapshots)
            assertSame(canonicalSnapshot, runtime.snapshots.state.value.snapshot)

            // Artwork ↔ timed lyrics toggling must retain the same current occurrence/song.
            composeRule.onNodeWithTag("player-artwork-stage").performClick()
            composeRule.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            composeRule.onNodeWithText("First integration lyric").assertExists()
            composeRule.onNodeWithText("Second integration lyric").assertExists()
            composeRule.onNodeWithTag("lyrics-return-artwork").performClick()
            composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
            composeRule.onNodeWithText("Task 4 integration title").assertExists()

            composeRule.onNodeWithTag("player-bottom-queue").performClick()
            composeRule.waitUntil(5_000) {
                navController.currentDestination?.route == AppDestination.QUEUE.route &&
                    composeRule.onAllNodesWithTag("queue-screen")
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            composeRule.onNodeWithTag("queue-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("queue-row-occ-current").assertIsDisplayed()
            composeRule.onNodeWithTag("queue-row-current-label-occ-current", useUnmergedTree = true)
                .assertTextEquals("已暂停")
            composeRule.onNodeWithTag("queue-row-occ-pending-duplicate").assertIsDisplayed()

            // Exercise the actual routed row menu without selecting an operation.
            composeRule.onNodeWithTag("queue-row-menu-occ-pending-duplicate").performClick()
            composeRule.onNodeWithTag("queue-row-menu-panel").assertIsDisplayed()
            composeRule.onNodeWithTag("queue-menu-next").assertIsDisplayed()
            composeRule.onNodeWithTag("queue-menu-add").assertIsDisplayed()
            composeRule.onNodeWithTag("queue-menu-remove").assertIsDisplayed()
            composeRule.onNodeWithTag("queue-menu-cancel").performClick()
            composeRule.onAllNodesWithTag("queue-row-menu-panel").assertCountEquals(0)

            // A cancelled drag is local-only: it must never become a reorder mutation.
            val source = composeRule.onNodeWithTag(
                "queue-row-drag-handle-occ-pending-duplicate",
                useUnmergedTree = true,
            ).fetchSemanticsNode().boundsInRoot.center
            val target = composeRule.onNodeWithTag("queue-row-occ-pending-next")
                .fetchSemanticsNode().boundsInRoot.center
            composeRule.onNodeWithTag(
                "queue-row-drag-handle-occ-pending-duplicate",
                useUnmergedTree = true,
            ).performTouchInput {
                down(center)
                advanceEventTime(700L)
                moveBy(Offset(0f, target.y - source.y - 8f))
                advanceEventTime(100L)
                cancel()
            }
            composeRule.waitForIdle()

            assertSame(canonicalStore, runtime.snapshots)
            assertSame(canonicalSnapshot, runtime.snapshots.state.value.snapshot)
            assertEquals("integration-epoch", runtime.snapshots.state.value.snapshot?.epoch)
            assertEquals(9L, runtime.snapshots.state.value.snapshot?.sequence)
            assertTrue("the cross-screen journey must not submit any write", runtime.mutations.activities.value.isEmpty())

            composeRule.onNodeWithTag("player-bottom-now-playing").performClick()
            composeRule.waitUntil(5_000) {
                navController.currentDestination?.route == AppDestination.FULL_SCREEN_PLAYER.route
            }
            composeRule.onNodeWithText("Task 4 integration title").assertExists()
            assertSame(canonicalSnapshot, runtime.snapshots.state.value.snapshot)

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitUntil(5_000) {
                navController.currentDestination?.route == AppDestination.HOME.route
            }
            composeRule.onNodeWithTag("mini-player-body").assertIsDisplayed()
            composeRule.onAllNodesWithTag("full-screen-player-root").assertCountEquals(0)
            assertSame(canonicalStore, runtime.snapshots)
            assertSame(canonicalSnapshot, runtime.snapshots.state.value.snapshot)
            assertTrue(runtime.mutations.activities.value.isEmpty())
        } finally {
            runtime.close()
        }
    }

    private fun snapshotDto(): StateSnapshotDto {
        val json = JsonConfiguration.json
        val queueItems = listOf(
            Triple("occ-played-old", "song-main", -2),
            Triple("occ-played-latest", "song-main", -1),
            Triple("occ-current", "song-main", 0),
            Triple("occ-pending-duplicate", "song-main", 1),
            Triple("occ-pending-next", "song-next", 2),
        )
        val queue = """{"revision":17,"items":[${queueItems.joinToString(",") { (id, songId, position) ->
            """{"queue_item_id":"$id","song_id":"$songId","position":$position,"source":"MANUAL","playback_context_id":"ctx-$id"}"""
        }}]}"""
        val song = """
            {
              "song_id":"song-main","title":"Task 4 integration title","file_uri":"music/song-main.flac",
              "artists":["Integration Artist"],"album_id":"album-integration","album":"Integration Album",
              "album_artists":["Integration Artist"],"track_number":1,"disc_number":1,"year":2024,
              "date":"2024","genres":[],"tag_names":[],"duration":180.0,
              "lyrics":"[00:00.00]First integration lyric\n[00:01.00]Second integration lyric",
              "lyrics_format":"lrc","lyrics_source":"embedded","lyrics_status":"available",
              "bit_depth":24,"sample_rate_hz":96000,"channel_count":2,"codec":"FLAC",
              "metadata_status":"complete","last_scanned_at":null,"file_size":123,
              "file_mtime_ns":1,"content_hash":"test-hash","last_seen_at":null,"artwork":null
            }
        """.trimIndent()
        return StateSnapshotDto(
            epoch = "integration-epoch",
            sequence = 9,
            capturedAt = "2026-10-30T00:00:00Z",
            explicitStopGeneration = 0,
            revisions = StateRevisionsDto(library = 4, playlist = 2),
            playback = json.parseToJsonElement(
                """{"song_id":"song-main","state":"PAUSED","playback_context_id":"ctx-current","position_seconds":1.5,"autoplay_enabled":false,"updated_at":"2026-10-30T00:00:00Z"}""",
            ),
            currentSong = json.parseToJsonElement(song),
            queue = json.parseToJsonElement(queue),
            history = json.parseToJsonElement("{}"),
            playbackObservation = json.parseToJsonElement(
                """
                {
                  "actual_state":"paused",
                  "actual_current":{"entry_id":1,"uri":"music/song-main.flac","position":0},
                  "actual_freshness":"fresh","bound_queue_item_id":"occ-current",
                  "control_target":{"queue_item_id":"occ-current","token":"integration-target"},
                  "sync_status":"CONFIRMED","matches_current":true,
                  "position_seconds":1.5,"duration_seconds":180.0,
                  "covered_playback_updated_at":"2026-10-30T00:00:00Z",
                  "observed_at":"2026-10-30T00:00:01Z","freshness":"fresh",
                  "reconciliation_required":false,"error_code":null,"error_message":null
                }
                """.trimIndent(),
            ),
            output = json.parseToJsonElement("""{"states":[],"last_request":null}"""),
            outputObservation = json.parseToJsonElement(
                """{"observed_at":"2026-10-30T00:00:01Z","freshness":"fresh","error_code":null,"error_message":null}""",
            ),
            librarySync = json.parseToJsonElement("""{"generation":1}"""),
        )
    }
}
