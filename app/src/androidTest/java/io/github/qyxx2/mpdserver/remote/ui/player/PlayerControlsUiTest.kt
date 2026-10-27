package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerControlsUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun confirmedPlaybackExposesOnlySupportedControlsAndOneSeekReleaseSubmitsOnce() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = state()
        val submitted = mutableListOf<MutationIntent>()
        val actions = actions(state, submitted)
        actions.update(state, emptyList())

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = PlayerPresentation.from(state),
                    actions = actions,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        listOf("player-previous", "player-play-pause", "player-next").forEach {
            composeRule.onNodeWithTag(it).assertIsEnabled()
        }
        composeRule.onNodeWithTag("player-progress-slider").assertIsDisplayed().assertIsEnabled()

        // A normal progress drag previews locally and commits exactly one offset-free seek on release.
        val slider = composeRule.onNodeWithTag("player-progress-slider")
        val bounds = slider.fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("player-progress-slider").performTouchInput {
            down(Offset(width * 0.20f, height / 2f))
            moveTo(Offset(width * 0.72f, height / 2f), delayMillis = 180)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, submitted.size)
        assertEquals("/api/playback/seek", submitted.single().path)
        val body = JsonConfiguration.json.parseToJsonElement(submitted.single().payload!!).jsonObject
        assertEquals("occurrence-1", body.getValue("target").jsonObject
            .getValue("queue_item_id").jsonPrimitive.content)
        val seconds = body.getValue("seconds").jsonPrimitive.double
        assertNotNull(seconds)
        assertTrue(seconds in 0.0..123.5)

        // Starting another action while this operation is unresolved must not enqueue a second write.
        composeRule.onNodeWithTag("player-play-pause").assertIsNotEnabled()
        assertEquals(1, submitted.size)
        assert(bounds.width > 0f)
    }

    @Test
    fun staleSnapshotDisablesPauseNavigationAndSeek() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = state(freshness = SnapshotFreshness.STALE)
        val actions = actions(state, mutableListOf())
        actions.update(state, emptyList())

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = PlayerPresentation.from(state),
                    actions = actions,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        listOf("player-previous", "player-play-pause", "player-next", "player-progress-slider").forEach {
            composeRule.onNodeWithTag(it).assertIsNotEnabled()
        }
        assertNull(actions.state.value.seekDraftSeconds)
    }

    @Test
    fun cancellingSliderGestureDoesNotDispatchOrLeaveDraft() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val state = state()
        val submitted = mutableListOf<MutationIntent>()
        val actions = actions(state, submitted)
        actions.update(state, emptyList())

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = PlayerPresentation.from(state),
                    actions = actions,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-progress-slider").performTouchInput {
            down(Offset(width * 0.25f, height / 2f))
            moveTo(Offset(width * 0.65f, height / 2f), delayMillis = 120)
            cancel()
        }
        composeRule.waitForIdle()
        assertEquals(0, submitted.size)
        assertNull(actions.state.value.seekDraftSeconds)
    }

    private fun actions(
        state: SnapshotStoreState,
        submitted: MutableList<MutationIntent>,
    ) = PlayerActions(
        stateProvider = { state },
        activitiesProvider = { emptyList() },
        submitter = { intent ->
            submitted += intent
            MutationOutcome.Submitted
        },
        retryUnknownSubmitter = { MutationOutcome.NotRetryable },
        idFactory = { "ui-test-intent" },
    )

    private fun state(
        freshness: SnapshotFreshness = SnapshotFreshness.FRESH,
    ): SnapshotStoreState {
        val json = JsonConfiguration.json
        val dto = StateSnapshotDto(
            epoch = "player-ui-test-epoch",
            sequence = 1,
            capturedAt = "2026-10-27T10:00:01Z",
            explicitStopGeneration = 0,
            revisions = StateRevisionsDto(library = 1, playlist = 1),
            playback = json.parseToJsonElement(
                """{"song_id":"song-1","state":"PLAYING","context":null,"position":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}""",
            ),
            currentSong = null,
            queue = json.parseToJsonElement(
                """{"revision":1,"items":[{"queue_item_id":"played-1","song_id":"song-0","position":-1,"source":"MANUAL"},{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"next-1","song_id":"song-2","position":1,"source":"MANUAL"}]}""",
            ),
            history = json.parseToJsonElement("""{"items":[]}"""),
            playbackObservation = json.parseToJsonElement(
                """{"actual_state":"playing","actual_current":{"entry_id":5,"uri":"music/example.flac","position":0},"actual_freshness":"fresh","bound_queue_item_id":"occurrence-1","control_target":{"queue_item_id":"occurrence-1","token":"opaque-token"},"sync_status":"CONFIRMED","matches_current":true,"position_seconds":12.5,"duration_seconds":123.5,"covered_playback_updated_at":"2026-10-27T10:00:00Z","observed_at":"2026-10-27T10:00:01Z","freshness":"fresh","reconciliation_required":false,"error_code":null,"error_message":null}""",
            ),
            output = json.parseToJsonElement("""{"states":[],"last_request":null}"""),
            outputObservation = json.parseToJsonElement(
                """{"observed_at":null,"freshness":"unknown","error_code":null,"error_message":null}""",
            ),
            librarySync = json.parseToJsonElement("""{"generation":1}"""),
        )
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = RuntimeConnectionStatus.CONNECTED,
            connectionGeneration = 1,
            snapshotConnectionGeneration = 1,
            freshness = freshness,
        )
    }
}
