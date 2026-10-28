package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
    fun quietUnknownHandoffKeepsLastTrackVisibleWithoutEnablingCanonicalControls() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val song = songResponseJson()
        val confirmed = state(currentSongJson = song)
        val unknown = state(currentSongJson = song, observationJson = quietUnknownObservationJson())
        val actions = actions(unknown, mutableListOf())
        actions.update(unknown, emptyList())

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = PlayerPresentation.from(unknown),
                    mediaPresentation = PlayerPresentation.from(confirmed),
                    mediaHandoffActive = true,
                    mediaHandoffPositionSeconds = 12.5,
                    actions = actions,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-song-title").assertIsDisplayed()
        composeRule.onAllNodesWithTag("player-song-unconfirmed").assertCountEquals(0)
        composeRule.onNodeWithTag("player-play-pause").assertIsNotEnabled()
        composeRule.onNodeWithTag("player-progress-slider").assertIsNotEnabled()
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

    private fun confirmedObservationJson() =
        """{"actual_state":"playing","actual_current":{"entry_id":5,"uri":"music/example.flac","position":0},"actual_freshness":"fresh","bound_queue_item_id":"occurrence-1","control_target":{"queue_item_id":"occurrence-1","token":"opaque-token"},"sync_status":"CONFIRMED","matches_current":true,"position_seconds":12.5,"duration_seconds":123.5,"covered_playback_updated_at":"2026-10-27T10:00:00Z","observed_at":"2026-10-27T10:00:01Z","freshness":"fresh","reconciliation_required":false,"error_code":null,"error_message":null}"""

    private fun quietUnknownObservationJson() =
        """{"actual_state":null,"actual_current":null,"actual_freshness":"unknown","bound_queue_item_id":null,"control_target":null,"sync_status":"UNBOUND","matches_current":null,"position_seconds":null,"duration_seconds":null,"covered_playback_updated_at":null,"observed_at":null,"freshness":"unknown","reconciliation_required":false,"error_code":null,"error_message":null}"""

    private fun songResponseJson() =
        """{"song_id":"song-1","title":"Example","file_uri":"music/example.flac","artists":["Artist A"],"album_id":"album-1","album":"Album","album_artists":["Album Artist"],"track_number":3,"disc_number":1,"year":2024,"date":"2024-05-01","genres":["Jazz"],"tag_names":["HiRes"],"duration":123.5,"lyrics":"","lyrics_format":null,"lyrics_source":null,"lyrics_status":"missing","bit_depth":24,"sample_rate_hz":96000,"channel_count":2,"codec":"FLAC","metadata_status":"complete","last_scanned_at":"2026-10-27T10:00:00Z","file_size":123456,"file_mtime_ns":123456789,"content_hash":"sha256:abc","availability_status":"AVAILABLE","last_seen_at":"2026-10-27T10:00:00Z","artwork":null}"""

    private fun state(
        freshness: SnapshotFreshness = SnapshotFreshness.FRESH,
        currentSongJson: String? = null,
        observationJson: String = confirmedObservationJson(),
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
            currentSong = currentSongJson?.let(json::parseToJsonElement),
            queue = json.parseToJsonElement(
                """{"revision":1,"items":[{"queue_item_id":"played-1","song_id":"song-0","position":-1,"source":"MANUAL"},{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"next-1","song_id":"song-2","position":1,"source":"MANUAL"}]}""",
            ),
            history = json.parseToJsonElement("""{"items":[]}"""),
            playbackObservation = json.parseToJsonElement(observationJson),
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
