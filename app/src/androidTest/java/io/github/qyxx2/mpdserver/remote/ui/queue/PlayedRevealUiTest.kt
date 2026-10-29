package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayedRevealUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dragReleaseCancelAndReverseMovementPreserveFractionalRevealAndShiftQueueLinearly() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val queueItems = listOf(
            item("played-oldest", "same-song", -3, QueueSourceDto.MANUAL),
            item("played-middle", "same-song", -2, QueueSourceDto.MANUAL),
            item("played-recent", "same-song", -1, QueueSourceDto.MANUAL),
            item("current", "same-song", 0, QueueSourceDto.MANUAL),
            item("pending", "next-song", 1, QueueSourceDto.MANUAL),
        )
        val state = snapshotState(queueItems, SnapshotFreshness.FRESH)
        val playedReads = AtomicInteger()
        val submitted = mutableListOf<MutationIntent>()
        val actions = QueueActions(
            stateProvider = { state },
            activitiesProvider = { emptyList() },
            submitter = { intent -> submitted += intent; MutationOutcome.Submitted },
            idFactory = { "b8-ui-test" },
        )
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueScreen(
                    runtime = application.runtime,
                    snapshotState = state,
                    presentation = PlayerPresentation.from(state),
                    onExit = {},
                    onOpenPlayer = {},
                    playedReader = {
                        playedReads.incrementAndGet()
                        ApiResult.Success(
                            QueueListDto(items = queueItems.filter { it.position < 0 }.sortedBy { it.position },
                                count = queueItems.count { it.position < 0 }),
                            200,
                        )
                    },
                    songResourceLoader = { _, _ -> throw IllegalStateException("Use fixed-height metadata placeholder") },
                    snapshotProvider = { state },
                    queueActionsFactory = { actions },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("queue-row-current").fetchSemanticsNode() }.isSuccess
        }

        val reveal = composeRule.onNodeWithTag("queue-played-reveal-region")
        val current = composeRule.onNodeWithTag("queue-row-current")
        val initialRevealHeight = reveal.fetchSemanticsNode().boundsInRoot.height
        val initialCurrentTop = current.fetchSemanticsNode().boundsInRoot.top
        assertEquals("fresh canonical Played is reused without a history GET", 0, playedReads.get())

        // Starting on a row action or drag handle must never be reinterpreted as Played reveal.
        composeRule.onNodeWithTag("queue-row-menu-current").performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
            advanceEventTime(60)
            up()
        }
        assertEquals(initialRevealHeight, reveal.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        composeRule.onNodeWithTag("queue-row-drag-handle-current", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
            advanceEventTime(60)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(initialRevealHeight, reveal.fetchSemanticsNode().boundsInRoot.height, 0.5f)

        composeRule.onNodeWithTag("queue-list").performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
            advanceEventTime(80)
            up()
        }
        composeRule.waitForIdle()
        val firstHeight = reveal.fetchSemanticsNode().boundsInRoot.height
        val firstCurrentTop = current.fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "finger movement continuously reveals a partial history region: initial=$initialRevealHeight first=$firstHeight currentTop=$initialCurrentTop newCurrentTop=$firstCurrentTop",
            firstHeight > initialRevealHeight + 45f,
        )
        assertEquals("the current row moves by the same revealed height", firstHeight - initialRevealHeight,
            firstCurrentTop - initialCurrentTop, 2f)
        composeRule.onNodeWithTag("queue-history-row-played-recent").assertExists()
        composeRule.onNodeWithTag("queue-history-row-played-oldest").assertExists()
        assertTrue(
            "the most recently Played occurrence stays closest to the current row, matching Web",
            composeRule.onNodeWithTag("queue-history-row-played-recent")
                .fetchSemanticsNode().boundsInRoot.top <
                composeRule.onNodeWithTag("queue-history-row-played-middle")
                    .fetchSemanticsNode().boundsInRoot.top,
        )

        composeRule.onNodeWithTag("queue-list").performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
            advanceEventTime(60)
            up()
        }
        composeRule.waitForIdle()
        val releasedHeight = reveal.fetchSemanticsNode().boundsInRoot.height
        assertTrue("the next gesture continues from the frozen fractional extent", releasedHeight > firstHeight + 25f)

        composeRule.onNodeWithTag("queue-list").performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
            advanceEventTime(60)
            cancel()
        }
        composeRule.waitForIdle()
        val cancelledHeight = reveal.fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "ACTION_CANCEL must freeze the partially revealed history at its current position",
            cancelledHeight > releasedHeight + 20f,
        )
        composeRule.waitForIdle()
        assertEquals(
            "cancel must not snap or continue moving after the pointer is gone",
            cancelledHeight,
            reveal.fetchSemanticsNode().boundsInRoot.height,
            0.5f,
        )

        composeRule.onNodeWithTag("queue-list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -60f))
            advanceEventTime(60)
            up()
        }
        composeRule.waitForIdle()
        val reverseHeight = reveal.fetchSemanticsNode().boundsInRoot.height
        assertTrue(
            "reverse drag reduces only the already revealed extent",
            reverseHeight < cancelledHeight - 20f,
        )
        assertTrue("reverse drag cannot create a negative history height", reverseHeight >= 0f)
        assertEquals("pointer movement never issues Played GETs", 0, playedReads.get())
        assertTrue("history reveal must remain presentation-only", submitted.isEmpty())
    }

    private fun snapshotState(
        items: List<QueueItemDto>,
        freshness: SnapshotFreshness,
        currentId: String? = items.firstOrNull { it.position == 0 }?.queueItemId,
    ): SnapshotStoreState {
        val json = JsonConfiguration.json
        val current = items.firstOrNull { it.position == 0 }
        val currentSongId = current?.songId ?: "no-current"
        val queueJson = """{"revision":7,"items":[${items.joinToString(",") { item ->
            """{"queue_item_id":"${item.queueItemId}","song_id":"${item.songId}","position":${item.position},"source":"${item.source.name}","playback_context_id":null}"""
        }}]}"""
        val currentSong = if (current == null) JsonNull else json.parseToJsonElement(songJson(current.songId, "Server title"))
        val observation = """{
            "actual_state":"playing",
            "actual_current":{"entry_id":1,"uri":"music/current.flac","position":0},
            "actual_freshness":"fresh",
            "bound_queue_item_id":${currentId?.let { "\"$it\"" } ?: "null"},
            "control_target":{"queue_item_id":${currentId?.let { "\"$it\"" } ?: "null"},"token":"test-token"},
            "sync_status":"CONFIRMED",
            "matches_current":${currentId != null},
            "position_seconds":12.5,
            "duration_seconds":123.5,
            "covered_playback_updated_at":"2026-10-28T10:00:00Z",
            "observed_at":"2026-10-28T10:00:01Z",
            "freshness":"fresh",
            "reconciliation_required":false,
            "error_code":null,
            "error_message":null
        }"""
        val dto = StateSnapshotDto(
            epoch = "queue-ui-test-epoch",
            sequence = 10,
            capturedAt = "2026-10-28T10:00:01Z",
            explicitStopGeneration = 0,
            revisions = StateRevisionsDto(library = 11, playlist = 2),
            playback = json.parseToJsonElement("""{"song_id":"$currentSongId","state":"PLAYING","playback_context_id":"context-test","position_seconds":12.5,"autoplay_enabled":true,"updated_at":"2026-10-28T10:00:00Z"}"""),
            currentSong = currentSong,
            queue = json.parseToJsonElement(queueJson),
            history = json.parseToJsonElement("{}"),
            playbackObservation = json.parseToJsonElement(observation),
            output = json.parseToJsonElement("""{"states":[],"last_request":null}"""),
            outputObservation = json.parseToJsonElement("""{"observed_at":"2026-10-28T10:00:01Z","freshness":"fresh","error_code":null,"error_message":null}"""),
            librarySync = json.parseToJsonElement("""{"generation":1}"""),
        )
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = RuntimeConnectionStatus.CONNECTED,
            connectionGeneration = 4,
            snapshotConnectionGeneration = 4,
            freshness = freshness,
        )
    }

    private fun songJson(id: String, title: String) = """
        {
          "song_id":"$id","title":"$title","file_uri":"music/$id.flac",
          "album_id":null,"album":null,"track_number":null,"disc_number":null,
          "year":null,"date":null,"duration":123.5,"lyrics":null,"lyrics_format":null,
          "lyrics_source":null,"bit_depth":24,"sample_rate_hz":96000,"channel_count":2,
          "codec":"FLAC","metadata_status":"complete","last_scanned_at":null,
          "file_size":null,"file_mtime_ns":null,"content_hash":null,"last_seen_at":null,
          "artwork":null
        }
    """.trimIndent()

    private fun item(id: String, songId: String, position: Int, source: QueueSourceDto) =
        QueueItemDto(id, songId, position, source, null)

}
