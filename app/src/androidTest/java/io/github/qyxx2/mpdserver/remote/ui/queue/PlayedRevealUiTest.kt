package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
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
import kotlinx.serialization.decodeFromString
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
    fun playedRowsShareTheQueueScrollAndNaturalFlingShowsRecentHistoryFirst() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val queueItems = listOf(
            item("played-oldest", "oldest-song", -3, QueueSourceDto.MANUAL),
            item("played-middle", "middle-song", -2, QueueSourceDto.MANUAL),
            item("played-recent", "recent-song", -1, QueueSourceDto.MANUAL),
            item("current", "current-song", 0, QueueSourceDto.MANUAL),
            item("pending", "next-song", 1, QueueSourceDto.MANUAL),
        )
        val state = snapshotState(queueItems, SnapshotFreshness.FRESH)
        val playedReads = AtomicInteger()
        val resourceLoads = AtomicInteger()
        val submitted = mutableListOf<MutationIntent>()
        val actions = QueueActions(
            stateProvider = { state },
            activitiesProvider = { emptyList() },
            submitter = { intent -> submitted += intent; MutationOutcome.Submitted },
            idFactory = { "history-natural-scroll-test" },
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
                            QueueListDto(
                                items = queueItems.filter { it.position < 0 }.sortedBy { it.position },
                                count = queueItems.count { it.position < 0 },
                            ),
                            200,
                        )
                    },
                    songResourceLoader = { _, songId ->
                        resourceLoads.incrementAndGet()
                        QueueSongResource(
                            song = JsonConfiguration.json.decodeFromString<SongDto>(
                                songJson(songId, "Prefetched $songId"),
                            ),
                        )
                    },
                    snapshotProvider = { state },
                    queueActionsFactory = { actions },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("queue-row-current").fetchSemanticsNode() }.isSuccess
        }

        val list = composeRule.onNodeWithTag("queue-list")
        val current = composeRule.onNodeWithTag("queue-row-current")
        composeRule.waitUntil(5_000) {
            runCatching {
                val listTop = list.fetchSemanticsNode().boundsInRoot.top
                val currentTop = current.fetchSemanticsNode().boundsInRoot.top
                currentTop - listTop in 0f..80f
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        val initialListTop = list.fetchSemanticsNode().boundsInRoot.top
        val initialCurrentTop = current.fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "current must open at the top of the scroll viewport: list=$initialListTop current=$initialCurrentTop delta=${initialCurrentTop - initialListTop}",
            initialCurrentTop - initialListTop in 0f..80f,
        )
        assertTrue("history must not be a separately clipped reveal region",
            runCatching { composeRule.onNodeWithTag("queue-played-reveal-region").fetchSemanticsNode() }.isFailure)
        assertEquals("fresh canonical history is reused without an extra GET", 0, playedReads.get())
        composeRule.waitUntil(5_000) { resourceLoads.get() >= queueItems.size }
        val prewarmedResourceLoads = resourceLoads.get()
        assertTrue("metadata should begin loading before any scroll gesture", prewarmedResourceLoads >= queueItems.size)

        val fingerTravelPx = 220f
        current.performTouchInput {
            swipeDown(
                startY = center.y,
                endY = center.y + fingerTravelPx,
                durationMillis = 100,
            )
        }
        composeRule.waitUntil(5_000) {
            runCatching { composeRule.onNodeWithTag("queue-history-row-played-recent").fetchSemanticsNode() }.isSuccess
        }
        composeRule.waitForIdle()

        val recentTop = composeRule.onNodeWithTag("queue-history-row-played-recent").fetchSemanticsNode().boundsInRoot.top
        val currentTopAfterFling = current.fetchSemanticsNode().boundsInRoot.top
        assertTrue("the most recent Played row should be immediately before current", recentTop < currentTopAfterFling)
        assertTrue(
            "LazyColumn fling should continue beyond finger travel: initial=$initialCurrentTop " +
                "final=$currentTopAfterFling finger=$fingerTravelPx",
            currentTopAfterFling - initialCurrentTop > fingerTravelPx + 32f,
        )
        assertEquals("scrolling does not trigger another Played GET", 0, playedReads.get())
        assertEquals("prewarmed song metadata must not be fetched again during scrolling",
            prewarmedResourceLoads, resourceLoads.get())
        assertTrue("history scrolling never submits playback or Queue mutations", submitted.isEmpty())
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
