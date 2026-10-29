package io.github.qyxx2.mpdserver.remote.ui.queue

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class QueueScreenUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun queueRowColorsKeepTheCurrentHueAndLightnessButSlightlyIncreaseSaturation() {
        val original = Color(0xFFA84F0B)
        val highlighted = queueOccurrenceBackgroundColor(isCurrent = true, primary = original)
        val originalHsl = FloatArray(3)
        val highlightedHsl = FloatArray(3)
        ColorUtils.colorToHSL(original.toArgb(), originalHsl)
        ColorUtils.colorToHSL(highlighted.toArgb(), highlightedHsl)

        assertEquals("keep the current highlight opacity", 0.10f, highlighted.alpha, 0.003f)
        assertEquals("keep its hue", originalHsl[0], highlightedHsl[0], 0.5f)
        assertEquals("keep its lightness", originalHsl[2], highlightedHsl[2], 0.01f)
        assertTrue("increase saturation without turning the color into a vivid orange", highlightedHsl[1] > originalHsl[1])
        assertTrue("cap saturation to keep the selected row restrained", highlightedHsl[1] <= 0.93f)
        assertEquals(
            "non-current items must be fully transparent",
            Color.Transparent,
            queueOccurrenceBackgroundColor(isCurrent = false, primary = original),
        )
    }

    @Test
    fun freshSnapshotShowsCurrentThenWebNextUsingDistinctOccurrenceKeysAndDoesNotReadPlayedAgain() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val items = listOf(
            item("occ-current", "song-duplicate", 0, QueueSourceDto.MANUAL),
            item("occ-manual-late", "song-duplicate", 3, QueueSourceDto.MANUAL),
            item("occ-auto", "song-auto", 2, QueueSourceDto.AUTOPLAY),
            item("occ-manual-first", "song-duplicate", 1, QueueSourceDto.MANUAL),
            item("occ-played", "song-old", -1, QueueSourceDto.MANUAL),
        )
        val state = snapshotState(items, freshness = SnapshotFreshness.FRESH)
        val songReads = AtomicInteger()
        val playedReads = AtomicInteger()
        val metadataGate = CompletableDeferred<Unit>()

        launchQueue(
            application,
            state,
            songReads,
            playedReads,
            listOf(item("fallback-played", "fallback", -1, QueueSourceDto.MANUAL)),
            songLoadGate = metadataGate,
        )

        try {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithTag("queue-row-title-occ-current")
                        .fetchSemanticsNode().config[SemanticsProperties.Text]
                        .any { it.text == "正在读取曲目信息…" }
                }.getOrDefault(false)
            }
            val placeholderHeight = composeRule.onNodeWithTag("queue-row-occ-current")
                .fetchSemanticsNode().boundsInRoot.height
            metadataGate.complete(Unit)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    composeRule.onNodeWithTag("queue-row-title-occ-current")
                        .fetchSemanticsNode().config[SemanticsProperties.Text]
                        .any { it.text == "Title song-duplicate" }
                }.getOrDefault(false)
            }
            val loadedHeight = composeRule.onNodeWithTag("queue-row-occ-current")
                .fetchSemanticsNode().boundsInRoot.height
            assertEquals("metadata replacement must preserve the row's measured height", placeholderHeight, loadedHeight, 0.5f)
        } finally {
            metadataGate.complete(Unit)
        }
        composeRule.onNodeWithTag("queue-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-occ-current").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-title-occ-current").assertTextEquals("Title song-duplicate")
        composeRule.onNodeWithTag("queue-row-occ-manual-first").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-occ-manual-late").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-current-label-occ-current").assertTextEquals("正在播放")
        composeRule.onNodeWithTag("queue-row-divider-occ-current").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-divider-occ-manual-first").assertIsDisplayed()
        composeRule.onAllNodesWithTag("queue-row-occ-auto").assertCountEquals(0)
        composeRule.onAllNodesWithTag("queue-row-occ-played").assertCountEquals(0)
        composeRule.onAllNodesWithTag("queue-row-fallback-played").assertCountEquals(0)

        val currentTop = composeRule.onNodeWithTag("queue-row-occ-current").fetchSemanticsNode().boundsInRoot.top
        val firstPendingTop = composeRule.onNodeWithTag("queue-row-occ-manual-first").fetchSemanticsNode().boundsInRoot.top
        assertTrue("the business current stays at the top of the list", currentTop < firstPendingTop)
        composeRule.waitForIdle()
        assertEquals("all duplicate occurrences share one metadata read by song_id", 1, songReads.get())
        assertEquals("a fresh canonical Played resource must be reused", 0, playedReads.get())
    }

    @Test
    fun canonicalPlaybackWireShapeShowsOnlyTheFirstAutoplayCandidate() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState(
            items = listOf(
                item("occ-current-auto", "song-current", 0, QueueSourceDto.AUTOPLAY),
                item("occ-next-auto", "song-next", 1, QueueSourceDto.AUTOPLAY),
                item("occ-later-auto", "song-later", 2, QueueSourceDto.AUTOPLAY),
            ),
            freshness = SnapshotFreshness.FRESH,
        )
        val songReads = AtomicInteger()
        val playedReads = AtomicInteger()

        launchQueue(application, state, songReads, playedReads, emptyList())

        composeRule.onNodeWithTag("queue-row-occ-current-auto").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-occ-next-auto").assertIsDisplayed()
        composeRule.onAllNodesWithTag("queue-row-occ-later-auto").assertCountEquals(0)
        assertEquals("fresh canonical Played must not trigger a fallback read", 0, playedReads.get())
    }

    @Test
    fun staleSnapshotShowsBusinessCurrentAsUnconfirmedAndReadsPlayedOnce() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val items = listOf(
            item("occ-current", "song-current", 0, QueueSourceDto.MANUAL),
            item("snapshot-played", "stale-played", -1, QueueSourceDto.MANUAL),
        )
        val state = snapshotState(items, freshness = SnapshotFreshness.STALE)
        val songReads = AtomicInteger()
        val playedReads = AtomicInteger()

        launchQueue(application, state, songReads, playedReads, listOf(item("fallback-played", "song-fallback", -1, QueueSourceDto.MANUAL)))

        composeRule.waitUntil(timeoutMillis = 5_000) { playedReads.get() == 1 }
        composeRule.onNodeWithTag("queue-stale-state").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-row-current-label-occ-current")
            .assertTextEquals("业务当前 · 实际播放未确认")
        composeRule.onNodeWithTag("queue-row-occ-current").assertIsDisplayed()
        composeRule.onAllNodesWithTag("queue-row-snapshot-played").assertCountEquals(0)
        composeRule.onAllNodesWithTag("queue-row-fallback-played").assertCountEquals(0)
        composeRule.waitForIdle()
        assertEquals("stale Played is fetched at most once for this route/session", 1, playedReads.get())
    }

    @Test
    fun queueWithoutCurrentDoesNotInventCurrentOrDuplicateRows() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState(
            listOf(
                item("pending-manual", "song-pending", 2, QueueSourceDto.MANUAL),
                item("pending-auto", "song-auto", 1, QueueSourceDto.AUTOPLAY),
            ),
            freshness = SnapshotFreshness.FRESH,
            currentId = null,
        )
        val songReads = AtomicInteger()
        val playedReads = AtomicInteger()

        launchQueue(application, state, songReads, playedReads, emptyList())

        composeRule.onNodeWithTag("queue-row-pending-manual").assertIsDisplayed()
        composeRule.onAllNodesWithTag("queue-row-pending-auto").assertCountEquals(0)
        composeRule.onAllNodesWithTag("queue-row-current-label-pending-manual").assertCountEquals(0)
        assertEquals(0, playedReads.get())
    }

    @Test
    fun rowMenuAndQueueToolsSubmitOnlyTheirConfirmedActions() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState(
            listOf(
                item("current", "same-song", 0, QueueSourceDto.MANUAL),
                item("pending-a", "same-song", 1, QueueSourceDto.MANUAL),
                item("pending-b", "same-song", 2, QueueSourceDto.MANUAL),
            ),
            freshness = SnapshotFreshness.FRESH,
        )
        val submitted = mutableListOf<MutationIntent>()
        val actions = QueueActions(
            stateProvider = { state },
            activitiesProvider = { emptyList() },
            submitter = { submitted += it; MutationOutcome.Submitted },
            idFactory = { "ui-test-${submitted.size}" },
        )
        launchQueue(
            application = application,
            state = state,
            songReads = AtomicInteger(),
            playedReads = AtomicInteger(),
            played = emptyList(),
            queueActionsFactory = { actions },
        )

        composeRule.onNodeWithTag("queue-row-pending-a").performClick()
        assertEquals("POST", submitted.single().method)
        assertEquals("/api/playback/queue/items/pending-a/play", submitted.single().path)

        composeRule.onNodeWithTag("queue-row-menu-pending-b").performClick()
        composeRule.onNodeWithTag("queue-menu-next").assertIsDisplayed().performClick()
        assertEquals(2, submitted.size)
        assertEquals("PUT", submitted[1].method)
        assertEquals("/api/playback/queue/items/pending-b", submitted[1].path)
        assertTrue(submitted[1].payload!!.contains("pending-a"))

        composeRule.onNodeWithTag("queue-clear-button").performClick()
        composeRule.onNodeWithTag("queue-clear-dialog").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals("cancel must not mutate the Queue", 2, submitted.size)

        composeRule.onNodeWithTag("queue-clear-button").performClick()
        composeRule.onNodeWithTag("queue-clear-confirm").performClick()
        assertEquals("DELETE", submitted[2].method)
        assertEquals("/api/playback/queue", submitted[2].path)
        assertTrue(submitted.none { it.path.contains("/stop") })

        composeRule.onNodeWithTag("queue-save-button").performClick()
        composeRule.onNodeWithTag("queue-playlist-name").performTextInput("Road Trip")
        composeRule.onNodeWithTag("queue-save-confirm").performClick()
        assertEquals("POST", submitted[3].method)
        assertEquals("/api/playback/queue/save-as-playlist", submitted[3].path)
        assertTrue(submitted[3].payload!!.contains("Road Trip"))
    }

    @Test
    fun queueToolbarShowsRemainingTimeAndCompactActionsWithoutTitle() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState(
            listOf(item("current", "song-current", 0, QueueSourceDto.MANUAL)),
            freshness = SnapshotFreshness.FRESH,
        )
        launchQueue(application, state, AtomicInteger(), AtomicInteger(), emptyList())

        composeRule.onNodeWithTag("queue-remaining-time").assertTextEquals("剩余播放时间：--:--")
        composeRule.onNodeWithTag("queue-clear-button").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-clear-icon", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("queue-save-button").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-save-icon", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("队列").assertCountEquals(0)
    }

    @Test
    fun rowMenuIsCompactNearItsTriggerAndDismissesWhenTappingOutside() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = snapshotState(
            listOf(
                item("current", "song-current", 0, QueueSourceDto.MANUAL),
                item("pending", "song-pending", 1, QueueSourceDto.MANUAL),
            ),
            freshness = SnapshotFreshness.FRESH,
        )
        launchQueue(
            application,
            state,
            AtomicInteger(),
            AtomicInteger(),
            emptyList(),
            queueActionsFactory = {
                QueueActions(
                    stateProvider = { state },
                    activitiesProvider = { emptyList() },
                    submitter = { MutationOutcome.Submitted },
                    idFactory = { "outside-tap-test" },
                )
            },
        )

        composeRule.onNodeWithTag("queue-row-menu-pending").performClick()
        composeRule.onNodeWithTag("queue-row-menu-panel").assertIsDisplayed()
        val panel = composeRule.onNodeWithTag("queue-row-menu-panel").fetchSemanticsNode().boundsInRoot
        val screenWidth = composeRule.activity.resources.displayMetrics.widthPixels.toFloat()
        assertTrue(
            "The row menu must stay compact rather than filling/centering across the screen: $panel",
            panel.width < screenWidth * 0.80f,
        )

        // Compose's test-node touch targets the underlying host view directly and bypasses a
        // focusable Popup window. Inject a real screen touch so Android routes it outside the menu.
        val decor = composeRule.activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val x = location[0] + decor.width * 0.10f // far left of the row-anchored popup
        val y = location[1] + decor.height * 0.78f // blank space below the visible queue rows
        val downTime = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
            instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("queue-row-menu-panel").assertCountEquals(0)
    }

    private fun launchQueue(
        application: MpdServerRemoteApplication,
        state: SnapshotStoreState,
        songReads: AtomicInteger,
        playedReads: AtomicInteger,
        played: List<QueueItemDto>,
        songLoadGate: CompletableDeferred<Unit>? = null,
        queueActionsFactory: (() -> QueueActions)? = null,
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
                    playedReader = {
                        playedReads.incrementAndGet()
                        ApiResult.Success(QueueListDto(items = played, count = played.size), 200)
                    },
                    songResourceLoader = { _, songId ->
                        songReads.incrementAndGet()
                        songLoadGate?.await()
                        QueueSongResource(song(songId, "Title $songId"))
                    },
                    snapshotProvider = { state },
                    queueActionsFactory = queueActionsFactory,
                )
            }
        }
        composeRule.waitForIdle()
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

    private fun song(id: String, title: String): SongDto =
        JsonConfiguration.json.decodeFromString(songJson(id, title))

    private fun item(id: String, songId: String, position: Int, source: QueueSourceDto) =
        QueueItemDto(id, songId, position, source, null)
}
