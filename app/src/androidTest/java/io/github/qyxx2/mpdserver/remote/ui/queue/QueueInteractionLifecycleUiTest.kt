package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueInteractionLifecycleUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sameQueueHandleCanDragTwiceInOneComposition() {
        val fixture = launchQueue()

        dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = "pending-a")
        dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = "pending-c")

        assertEquals(
            listOf("pending-a", "pending-b", "pending-c"),
            fixture.pendingIds(),
        )
        assertInteractionReady("pending-b")
    }

    @Test
    fun differentQueueHandlesRemainDraggableAfterFirstDrag() {
        val fixture = launchQueue()

        dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = "pending-a")
        assertInteractionReady("pending-c")
        dragAndAcknowledge(fixture, sourceId = "pending-c", beforeId = "pending-b")

        assertEquals(
            listOf("pending-c", "pending-b", "pending-a"),
            fixture.pendingIds(),
        )
        assertInteractionReady("pending-c")
        assertInteractionReady("pending-b")
    }

    @Test
    fun dragThenImmediatelyOpenSameRowsOverflowMenu() {
        val fixture = launchQueue()

        dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = "pending-a")
        assertInteractionReady("pending-b")
        openMenu("pending-b")

        composeRule.onNodeWithTag("queue-menu-cancel").assertIsDisplayed()
        composeRule.onNodeWithTag("queue-menu-cancel").performClick()
        composeRule.onAllNodesWithTag("queue-row-menu-panel").assertCountEquals(0)
        assertInteractionReady("pending-b")
        assertEquals(1, fixture.submitted.size)
    }

    @Test
    fun dismissOverflowMenuThenDragQueueRow() {
        val fixture = launchQueue()

        openMenu("pending-b")
        composeRule.onNodeWithTag("queue-menu-cancel").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithTag("queue-row-menu-panel").assertCountEquals(0)

        dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = "pending-a")

        assertEquals(1, fixture.submitted.size)
        assertInteractionReady("pending-b")
    }

    @Test
    fun cancelledDragCanBeFollowedBySuccessfulDrag() {
        val fixture = launchQueue()

        drag(fixture, sourceId = "pending-b", beforeId = "pending-a", cancel = true)
        composeRule.waitForIdle()
        assertTrue("cancelled pointer gesture must not submit a reorder", fixture.submitted.isEmpty())
        assertInteractionReady("pending-b")

        dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = "pending-a")
        assertEquals(1, fixture.submitted.size)
        assertInteractionReady("pending-b")
    }

    @Test
    fun repeatedDragAndMenuCyclesRemainAvailable() {
        val fixture = launchQueue()
        val targets = listOf("pending-a", "pending-c", "pending-a")

        targets.forEachIndexed { index, targetId ->
            dragAndAcknowledge(fixture, sourceId = "pending-b", beforeId = targetId)
            assertInteractionReady("pending-b")

            openMenu("pending-b")
            composeRule.onNodeWithTag("queue-menu-cancel").assertIsDisplayed().performClick()
            composeRule.onAllNodesWithTag("queue-row-menu-panel").assertCountEquals(0)

            assertEquals(
                "each cycle must submit exactly one reorder",
                index + 1,
                fixture.submitted.size,
            )
            assertInteractionReady("pending-b")
        }
    }

    private fun launchQueue(): QueueInteractionFixture {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val fixture = QueueInteractionFixture(snapshotState())
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                QueueScreen(
                    runtime = application.runtime,
                    snapshotState = fixture.state.value,
                    presentation = PlayerPresentation.from(fixture.state.value),
                    onExit = {},
                    onOpenPlayer = {},
                    playedReader = { ApiResult.Success(QueueListDto(), 200) },
                    songResourceLoader = { _, _ ->
                        throw IllegalStateException("Queue lifecycle test does not require remote Song reads")
                    },
                    snapshotProvider = fixture.snapshotProvider,
                    queueActionsFactory = fixture.actionsFactory,
                )
            }
        }
        composeRule.waitForIdle()
        assertInteractionReady("pending-b")
        return fixture
    }

    private fun dragAndAcknowledge(
        fixture: QueueInteractionFixture,
        sourceId: String,
        beforeId: String,
    ) {
        val submittedBefore = fixture.submitted.size
        drag(fixture, sourceId, beforeId)
        composeRule.waitForIdle()

        assertEquals(
            "drag must submit one occurrence reorder before the fixture readback",
            submittedBefore + 1,
            fixture.submitted.size,
        )
        val intent = fixture.submitted.last()
        assertEquals("PUT", intent.method)
        assertEquals("/api/playback/queue/items/$sourceId", intent.path)
        val actualBeforeId = JsonConfiguration.json.parseToJsonElement(intent.payload!!)
            .jsonObject.getValue("before_queue_item_id").jsonPrimitive.contentOrNull
        assertEquals("reorder must target the selected queue occurrence", beforeId, actualBeforeId)

        // QueueActions returns Submitted; emulate its matching authoritative readback before the
        // next gesture so this test cannot confuse the action lock with pointer-input failure.
        composeRule.runOnIdle { fixture.acknowledgeLatestReorder() }
        composeRule.waitForIdle()
        assertInteractionReady(sourceId)
    }

    private fun drag(
        fixture: QueueInteractionFixture,
        sourceId: String,
        beforeId: String,
        cancel: Boolean = false,
    ) {
        assertInteractionReady(sourceId)
        assertInteractionReady(beforeId)
        val sourceCenter = composeRule.onNodeWithTag(
            "queue-row-drag-handle-$sourceId",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot.center
        val targetCenter = composeRule.onNodeWithTag(
            "queue-row-$beforeId",
        ).fetchSemanticsNode().boundsInRoot.center
        val deltaY = targetCenter.y - sourceCenter.y - 8f

        composeRule.onNodeWithTag(
            "queue-row-drag-handle-$sourceId",
            useUnmergedTree = true,
        ).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, deltaY))
            advanceEventTime(120)
            if (cancel) cancel() else up()
        }
    }

    private fun openMenu(queueItemId: String) {
        assertInteractionReady(queueItemId)
        composeRule.onNodeWithTag("queue-row-menu-$queueItemId")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("queue-row-menu-panel").assertIsDisplayed()
    }

    private fun assertInteractionReady(queueItemId: String) {
        val handle = composeRule.onNodeWithTag(
            "queue-row-drag-handle-$queueItemId",
            useUnmergedTree = true,
        ).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("drag handle for $queueItemId must retain usable bounds", handle.width > 0f && handle.height > 0f)
        composeRule.onNodeWithTag("queue-row-menu-$queueItemId")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    private class QueueInteractionFixture(initial: SnapshotStoreState) {
        val state = mutableStateOf(initial)
        val submitted = mutableListOf<MutationIntent>()
        private val actions = QueueActions(
            stateProvider = { state.value },
            activitiesProvider = { emptyList() },
            submitter = { intent ->
                submitted += intent
                MutationOutcome.Submitted
            },
            idFactory = { "task8-queue-lifecycle-" + submitted.size },
        )
        val actionsFactory: () -> QueueActions = { actions }
        val snapshotProvider: () -> SnapshotStoreState = { state.value }

        fun pendingIds(): List<String> {
            val queue = JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(
                state.value.snapshot!!.wire.queue,
            )
            return queue.items.filter { it.position > 0 }
                .sortedBy { it.position }
                .map { it.queueItemId }
        }

        fun acknowledgeLatestReorder() {
            val intent = submitted.last()
            require(intent.method == "PUT")
            val queue = JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(
                state.value.snapshot!!.wire.queue,
            )
            val sourceId = intent.path.substringAfterLast('/')
            val beforeId = JsonConfiguration.json.parseToJsonElement(intent.payload!!)
                .jsonObject.getValue("before_queue_item_id").jsonPrimitive.contentOrNull
            val pending = queue.items.filter { it.position > 0 }
                .sortedBy { it.position }
                .toMutableList()
            val sourceIndex = pending.indexOfFirst { it.queueItemId == sourceId }
            require(sourceIndex >= 0) { "readback source $sourceId is not pending" }
            val moved = pending.removeAt(sourceIndex)
            val targetIndex = beforeId?.let { id ->
                pending.indexOfFirst { it.queueItemId == id }.also { require(it >= 0) }
            } ?: pending.size
            pending.add(targetIndex, moved)

            val current = queue.items.filter { it.position <= 0 }
            val canonicalItems = current + pending.mapIndexed { index, item ->
                item.copy(position = index + 1)
            }
            val updatedQueue = QueueSnapshotDto(
                revision = queue.revision + 1,
                items = canonicalItems,
            )
            val currentState = state.value
            val currentSnapshot = currentState.snapshot!!
            val updatedWire = currentSnapshot.wire.copy(
                sequence = currentSnapshot.wire.sequence + 1,
                queue = JsonConfiguration.json.encodeToJsonElement(
                    QueueSnapshotDto.serializer(),
                    updatedQueue,
                ),
            )
            state.value = currentState.copy(snapshot = FullStateSnapshot.from(updatedWire))
        }
    }

    private fun snapshotState(): SnapshotStoreState {
        val json = JsonConfiguration.json
        val items = listOf(
            QueueItemDto("current", "song-current", 0, QueueSourceDto.MANUAL, "ctx-current"),
            QueueItemDto("pending-a", "song-a", 1, QueueSourceDto.MANUAL, "ctx-a"),
            QueueItemDto("pending-b", "song-b", 2, QueueSourceDto.MANUAL, "ctx-b"),
            QueueItemDto("pending-c", "song-c", 3, QueueSourceDto.MANUAL, "ctx-c"),
        )
        val dto = StateSnapshotDto(
            epoch = "task8-queue-lifecycle",
            sequence = 5,
            capturedAt = "2026-11-02T00:00:00Z",
            explicitStopGeneration = 0,
            revisions = StateRevisionsDto(library = 1, playlist = 1),
            playback = json.parseToJsonElement(
                """{"song_id":"song-current","state":"PLAYING","playback_context_id":"context","position_seconds":12.0,"autoplay_enabled":false,"updated_at":"2026-11-02T00:00:00Z"}""",
            ),
            currentSong = json.parseToJsonElement(
                """{"song_id":"song-current","title":"Current","file_uri":"music/current.flac","album_id":null,"album":null,"track_number":null,"disc_number":null,"year":null,"date":null,"duration":123.0,"lyrics":null,"lyrics_format":null,"lyrics_source":null,"bit_depth":24,"sample_rate_hz":96000,"channel_count":2,"codec":"FLAC","metadata_status":"complete","last_scanned_at":null,"file_size":null,"file_mtime_ns":null,"content_hash":null,"last_seen_at":null,"artwork":null}""",
            ),
            queue = json.encodeToJsonElement(QueueSnapshotDto.serializer(), QueueSnapshotDto(17, items)),
            history = json.parseToJsonElement("{}"),
            playbackObservation = json.parseToJsonElement(
                """{"actual_state":"playing","actual_current":{"entry_id":1,"uri":"music/current.flac","position":0},"freshness":"fresh","actual_freshness":"fresh","matches_current":true,"bound_queue_item_id":"current","control_target":{"queue_item_id":"current","token":"test-token"},"sync_status":"CONFIRMED","position_seconds":12.0,"duration_seconds":123.0,"observed_at":"2026-11-02T00:00:00Z","covered_playback_updated_at":"2026-11-02T00:00:00Z","reconciliation_required":false,"error_code":null,"error_message":null}""",
            ),
            output = json.parseToJsonElement("""{"states":[],"last_request":null}"""),
            outputObservation = json.parseToJsonElement(
                """{"observed_at":"2026-11-02T00:00:00Z","freshness":"fresh","error_code":null,"error_message":null}""",
            ),
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
