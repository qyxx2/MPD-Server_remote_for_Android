package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test

class QueueReorderUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingLongPressDragSubmitsOneOccurrencePutAndWaitsForCanonicalReadback() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState()
        val submitted = mutableListOf<MutationIntent>()
        launchQueue(application, state, submitted)

        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("queue-row-drag-handle-pending-b").fetchSemanticsNode() }
                .isSuccess
        }
        val sourceCenter = composeRule.onNodeWithTag("queue-row-drag-handle-pending-b")
            .fetchSemanticsNode().boundsInRoot.center
        val targetCenter = composeRule.onNodeWithTag("queue-row-pending-a")
            .fetchSemanticsNode().boundsInRoot.center
        val upwardDelta = targetCenter.y - sourceCenter.y - 8f

        composeRule.onNodeWithTag("queue-row-drag-handle-pending-b").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, upwardDelta))
            advanceEventTime(120)
            up()
        }
        composeRule.waitForIdle()

        assertEquals("a completed gesture must submit exactly one reorder intent", 1, submitted.size)
        val intent = submitted.single()
        assertEquals("PUT", intent.method)
        assertEquals("/api/playback/queue/items/pending-b", intent.path)
        assertEquals(
            "pending-a",
            JsonConfiguration.json.parseToJsonElement(intent.payload!!).jsonObject
                .getValue("before_queue_item_id").jsonPrimitive.content,
        )
        val rowATop = composeRule.onNodeWithTag("queue-row-pending-a").fetchSemanticsNode().boundsInRoot.top
        val rowBTop = composeRule.onNodeWithTag("queue-row-pending-b").fetchSemanticsNode().boundsInRoot.top
        assertTrue("local drop must not rewrite the canonical row order before server readback", rowATop < rowBTop)
    }

    @Test
    fun cancelledPendingDragAndCurrentHandleNeverSubmitMutations() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState()
        val submitted = mutableListOf<MutationIntent>()
        launchQueue(application, state, submitted)

        val sourceCenter = composeRule.onNodeWithTag("queue-row-drag-handle-pending-b")
            .fetchSemanticsNode().boundsInRoot.center
        val targetCenter = composeRule.onNodeWithTag("queue-row-pending-a")
            .fetchSemanticsNode().boundsInRoot.center
        composeRule.onNodeWithTag("queue-row-drag-handle-pending-b").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, targetCenter.y - sourceCenter.y - 8f))
            advanceEventTime(100)
            cancel()
        }
        composeRule.waitForIdle()
        assertTrue("pointer cancellation must discard the local draft", submitted.isEmpty())

        composeRule.onNodeWithTag("queue-row-drag-handle-current").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, -120f))
            advanceEventTime(100)
            up()
        }
        composeRule.waitForIdle()
        assertTrue("current occurrence handles are decorative and cannot drag or trigger row play", submitted.isEmpty())
    }

    private fun launchQueue(
        application: MpdServerRemoteApplication,
        state: SnapshotStoreState,
        submitted: MutableList<MutationIntent>,
    ) {
        val presentation = PlayerPresentation.from(state)
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueScreen(
                    runtime = application.runtime,
                    snapshotState = state,
                    presentation = presentation,
                    onExit = {},
                    onOpenPlayer = {},
                    playedReader = { ApiResult.Success(QueueListDto(), 200) },
                    songResourceLoader = { _, _ -> throw IllegalStateException("test metadata intentionally unavailable") },
                    snapshotProvider = { state },
                    queueActionsFactory = {
                        QueueActions(
                            stateProvider = { state },
                            activitiesProvider = { emptyList() },
                            submitter = { intent -> submitted += intent; MutationOutcome.Submitted },
                            idFactory = { "b7-ui-${submitted.size}" },
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun snapshotState(): SnapshotStoreState {
        val json = JsonConfiguration.json
        val items = listOf(
            QueueItemDto("current", "same-song", 0, QueueSourceDto.MANUAL, "ctx-current"),
            QueueItemDto("pending-a", "same-song", 1, QueueSourceDto.MANUAL, "ctx-a"),
            QueueItemDto("pending-b", "same-song", 2, QueueSourceDto.MANUAL, "ctx-b"),
            QueueItemDto("pending-c", "other-song", 3, QueueSourceDto.MANUAL, "ctx-c"),
            QueueItemDto("played", "same-song", -1, QueueSourceDto.MANUAL, "ctx-played"),
        )
        val queue = """{"revision":17,"items":[${items.joinToString(",") { item ->
            """{"queue_item_id":"${item.queueItemId}","song_id":"${item.songId}","position":${item.position},"source":"${item.source.name}","playback_context_id":"${item.playbackContextId}"}"""
        }}]}"""
        val dto = StateSnapshotDto(
            epoch = "b7-ui-epoch",
            sequence = 5,
            capturedAt = "2026-10-29T04:00:00Z",
            explicitStopGeneration = 0,
            revisions = StateRevisionsDto(library = 1, playlist = 1),
            playback = json.parseToJsonElement("""{"song_id":"same-song","state":"PLAYING","playback_context_id":"context","position_seconds":12.0,"autoplay_enabled":false,"updated_at":"2026-10-29T04:00:00Z"}"""),
            currentSong = json.parseToJsonElement("""{
                "song_id":"same-song","title":"Current","file_uri":"music/current.flac",
                "album_id":null,"album":null,"track_number":null,"disc_number":null,
                "year":null,"date":null,"duration":123.0,"lyrics":null,"lyrics_format":null,
                "lyrics_source":null,"bit_depth":24,"sample_rate_hz":96000,"channel_count":2,
                "codec":"FLAC","metadata_status":"complete","last_scanned_at":null,
                "file_size":null,"file_mtime_ns":null,"content_hash":null,"last_seen_at":null,
                "artwork":null
            }"""),
            queue = json.parseToJsonElement(queue),
            history = json.parseToJsonElement("{}"),
            playbackObservation = json.parseToJsonElement("""{
                "actual_state":"playing","actual_current":{"entry_id":1,"uri":"music/current.flac","position":0},
                "freshness":"fresh","actual_freshness":"fresh","matches_current":true,"bound_queue_item_id":"current",
                "control_target":{"queue_item_id":"current","token":"test"},"sync_status":"CONFIRMED",
                "position_seconds":12.0,"duration_seconds":123.0,"observed_at":"2026-10-29T04:00:00Z",
                "covered_playback_updated_at":"2026-10-29T04:00:00Z","reconciliation_required":false,
                "error_code":null,"error_message":null
            }"""),
            output = json.parseToJsonElement("""{"states":[],"last_request":null}"""),
            outputObservation = json.parseToJsonElement("""{"observed_at":"2026-10-29T04:00:00Z","freshness":"fresh","error_code":null,"error_message":null}"""),
            librarySync = json.parseToJsonElement("""{"generation":1}"""),
        )
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = RuntimeConnectionStatus.CONNECTED,
            connectionGeneration = 4,
            snapshotConnectionGeneration = 4,
            freshness = SnapshotFreshness.FRESH,
        )
    }
}
