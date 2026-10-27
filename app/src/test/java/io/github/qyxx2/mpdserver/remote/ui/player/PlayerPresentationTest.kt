package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateValue
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import org.junit.Assert.*
import org.junit.Test

class PlayerPresentationTest {
    @Test fun requiresFreshConfirmedObservationBoundToPositionZeroOccurrence() {
        val confirmed = PlayerPresentation.from(PlayerTestFixtures.state())
        assertEquals(ConfirmedPlaybackStatus.PLAYING, confirmed.playbackStatus)
        assertEquals("occurrence-1", confirmed.currentOccurrenceId)
        assertTrue(confirmed.controls.pause)
        assertTrue(confirmed.controls.resume.not())
        assertFalse(confirmed.controls.previous)
        assertFalse(confirmed.controls.next)
        assertTrue(confirmed.controls.seek)

        val staleObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_freshness\":\"fresh\"", "\"actual_freshness\":\"stale\"")
        val stale = PlayerPresentation.from(PlayerTestFixtures.state(observation = staleObservation))
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, stale.playbackStatus)
        assertTrue(PlayerDiagnostic.ACTUAL_OBSERVATION_NOT_FRESH in stale.playbackDiagnostics)
        assertFalse(stale.controls.pause)

        val staleSnapshot = PlayerPresentation.from(
            PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE),
        )
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, staleSnapshot.playbackStatus)
        assertTrue(PlayerDiagnostic.SNAPSHOT_NOT_FRESH in staleSnapshot.playbackDiagnostics)
        assertFalse(staleSnapshot.controls.next)

        val disconnected = PlayerPresentation.from(
            PlayerTestFixtures.state(
                freshness = SnapshotFreshness.STALE,
                connectionStatus = RuntimeConnectionStatus.DISCONNECTED,
            ),
        )
        assertTrue(PlayerDiagnostic.CONNECTION_NOT_CONNECTED in disconnected.playbackDiagnostics)
        assertFalse(disconnected.controls.seek)
    }

    @Test fun doesNotTrustBusinessPlayingWhenObservationIsUnboundOrStale() {
        val unboundObservation = PlayerTestFixtures.observationJson
            .replace("\"sync_status\":\"CONFIRMED\"", "\"sync_status\":\"UNBOUND\"")
            .replace("\"matches_current\":true", "\"matches_current\":false")
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":null")
            .replace("\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}", "\"control_target\":null")
        val presentation = PlayerPresentation.from(
            PlayerTestFixtures.state(observation = unboundObservation),
        )

        assertEquals(PlaybackStateValue.PLAYING, presentation.businessPlaybackState)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, presentation.playbackStatus)
        assertTrue(PlayerDiagnostic.SYNC_NOT_CONFIRMED in presentation.playbackDiagnostics)
        assertFalse(presentation.controls.pause)
        assertFalse(presentation.controls.resume)
        assertFalse(presentation.controls.previous)
        assertFalse(presentation.controls.next)
        assertFalse(presentation.controls.seek)
    }

    @Test fun boundOccurrenceIdentityWinsOverDuplicateSongId() {
        val queue = """{"revision":18,"items":[{"queue_item_id":"occurrence-a","song_id":"song-1","position":0,"source":"MANUAL","playback_context_id":"ctx"},{"queue_item_id":"occurrence-b","song_id":"song-1","position":1,"source":"MANUAL","playback_context_id":"ctx"}]}"""
        val observation = PlayerTestFixtures.observationJson
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"occurrence-b\"")
            .replace("\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}", "\"control_target\":{\"queue_item_id\":\"occurrence-b\",\"token\":\"opaque-token\"}")
        val presentation = PlayerPresentation.from(
            PlayerTestFixtures.state(queue = queue, observation = observation),
        )

        assertEquals("occurrence-a", presentation.currentOccurrenceId)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, presentation.playbackStatus)
        assertTrue(PlayerDiagnostic.BOUND_OCCURRENCE_MISMATCH in presentation.playbackDiagnostics)
        assertNull(presentation.song)
    }

    @Test fun allowsNoCandidatesOnlyWhenAllOtherCurrentBindingsAreValid() {
        val observation = PlayerTestFixtures.observationJson
            .replace("\"sync_status\":\"CONFIRMED\"", "\"sync_status\":\"NO_CANDIDATES\"")
        val presentation = PlayerPresentation.from(PlayerTestFixtures.state(observation = observation))

        assertEquals(ConfirmedPlaybackStatus.PLAYING, presentation.playbackStatus)
    }

    @Test fun controlTargetMustMatchCurrentOccurrenceAndSeekNeedsAValidRange() {
        val mismatchedTarget = PlayerTestFixtures.observationJson.replace(
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
            "\"control_target\":{\"queue_item_id\":\"other-occurrence\",\"token\":\"opaque-token\"}",
        )
        val mismatchedPausedTarget = mismatchedTarget
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        val withoutTarget = PlayerPresentation.from(
            PlayerTestFixtures.state(observation = mismatchedPausedTarget),
        )
        assertEquals(ConfirmedPlaybackStatus.PAUSED, withoutTarget.playbackStatus)
        assertTrue(PlayerDiagnostic.CONTROL_TARGET_MISSING_OR_MISMATCH in withoutTarget.playbackDiagnostics)
        assertFalse(withoutTarget.controls.pause)
        assertFalse(withoutTarget.controls.resume)
        assertFalse(withoutTarget.controls.previous)
        assertFalse(withoutTarget.controls.next)
        assertFalse(withoutTarget.controls.seek)

        val noDuration = PlayerTestFixtures.observationJson
            .replace("\"duration_seconds\":123.5", "\"duration_seconds\":null")
        val noSeek = PlayerPresentation.from(PlayerTestFixtures.state(observation = noDuration))
        assertFalse(noSeek.controls.next)
        assertFalse(noSeek.controls.seek)
    }

    @Test fun pauseResumeCapabilityTracksConfirmedActualState() {
        val pausedObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        val paused = PlayerPresentation.from(PlayerTestFixtures.state(observation = pausedObservation))
        assertEquals(ConfirmedPlaybackStatus.PAUSED, paused.playbackStatus)
        assertFalse(paused.controls.pause)
        assertTrue(paused.controls.resume)
    }

    @Test fun sourceFormatIsIndependentFromOutputFormatAndMissingArtworkIsExplicit() {
        val changedOutput = PlayerTestFixtures.outputJson
            .replace("\"sample_rate\":96000", "\"sample_rate\":192000")
            .replace("\"bit_depth\":24", "\"bit_depth\":32")
        val presentation = PlayerPresentation.from(
            PlayerTestFixtures.state(output = changedOutput),
        )

        assertEquals("FLAC", presentation.sourceFormat.codec)
        assertEquals(96000, presentation.sourceFormat.sampleRateHz)
        assertEquals(24, presentation.sourceFormat.bitDepth)
        assertEquals(192000, presentation.output.dacSampleRateHz)
        assertEquals(32, presentation.output.dacBitDepth)
        assertFalse(presentation.output.describesBitPerfectOrPhysicalPlayback)
        assertEquals(ArtworkReferenceStatus.REFERENCED, presentation.song?.artworkReferenceStatus)

        val missingArtwork = PlayerTestFixtures.songJson.replace(
            "\"artwork\":{\"artwork_id\":\"art-1\",\"source\":\"EMBEDDED\",\"picture_index\":0,\"mime_type\":\"image/jpeg\",\"width\":1000,\"height\":1000,\"content_sha256\":\"abc\"}",
            "\"artwork\":null",
        )
        val noArtwork = PlayerPresentation.from(
            PlayerTestFixtures.state(song = missingArtwork),
        )
        assertEquals(ArtworkReferenceStatus.MISSING, noArtwork.song?.artworkReferenceStatus)
    }

    @Test fun staleOrUnknownOutputObservationNeverClaimsAnActiveOutput() {
        val staleObservation = PlayerTestFixtures.outputObservationJson
            .replace("\"freshness\":\"fresh\"", "\"freshness\":\"stale\"")
        val stale = PlayerPresentation.from(
            PlayerTestFixtures.state(outputObservation = staleObservation),
        )
        assertEquals(OutputObservationStatus.STALE, stale.output.observationStatus)
        assertEquals(DacOutputStatus.STALE, stale.output.dacStatus)

        val unknownObservation = PlayerTestFixtures.outputObservationJson
            .replace("\"freshness\":\"fresh\"", "\"freshness\":\"unknown\"")
        val unknown = PlayerPresentation.from(
            PlayerTestFixtures.state(outputObservation = unknownObservation),
        )
        assertEquals(OutputObservationStatus.UNKNOWN, unknown.output.observationStatus)
        assertEquals(DacOutputStatus.UNKNOWN, unknown.output.dacStatus)
    }

    @Test fun missingInvalidLyricsAndUnknownLyricsStatusStayDistinct() {
        val noSong = PlayerPresentation.from(PlayerTestFixtures.state(song = null))
        assertEquals(SongMetadataStatus.MISSING, noSong.songStatus)
        assertEquals(SourceFormatStatus.UNKNOWN, noSong.sourceFormat.status)
        assertEquals(ArtworkReferenceStatus.UNKNOWN, noSong.artworkReferenceStatus)

        val invalidSong = PlayerPresentation.from(
            PlayerTestFixtures.state(song = """{"not_a_song":true}"""),
        )
        assertEquals(SongMetadataStatus.INVALID, invalidSong.songStatus)

        val unknownLyrics = PlayerTestFixtures.songJson.replace(
            "\"lyrics_status\":\"available\"", "\"lyrics_status\":\"future_status\"",
        )
        val unknown = PlayerPresentation.from(PlayerTestFixtures.state(song = unknownLyrics))
        assertEquals(LyricsResourceStatus.UNKNOWN, unknown.song?.lyricsStatus)
        assertTrue(PlayerDiagnostic.LYRICS_STATUS_UNKNOWN in unknown.resourceDiagnostics)
    }

    @Test fun missingAndInvalidOutputFactsDoNotFallbackToPreviouslyKnownNumbers() {
        val noDac = PlayerTestFixtures.outputJson.replace(
            "\"states\":[{\"mode\":\"NAS_DAC\",\"status\":\"ACTIVE\",\"target_client_id\":null,\"stream_url\":null,\"format\":\"PCM\",\"sample_rate\":96000,\"bit_depth\":24,\"channels\":2,\"error_code\":null,\"error_message\":null,\"updated_at\":\"2026-10-27T10:00:00Z\",\"stale\":false}]",
            "\"states\":[]",
        )
        val noDacPresentation = PlayerPresentation.from(PlayerTestFixtures.state(output = noDac))
        assertEquals(DacOutputStatus.UNKNOWN, noDacPresentation.output.dacStatus)
        assertNull(noDacPresentation.output.dacSampleRateHz)

        val malformedOutput = PlayerPresentation.from(
            PlayerTestFixtures.state(output = """{"states":[{"mode":"FUTURE_MODE"}],"last_request":null}"""),
        )
        assertEquals(DacOutputStatus.ERROR, malformedOutput.output.dacStatus)
        assertNull(malformedOutput.output.dacSampleRateHz)
    }

    @Test fun falseCurrentMatchAndStoppedActualStateCannotConfirmPlayback() {
        val currentMismatch = PlayerTestFixtures.observationJson
            .replace("\"matches_current\":true", "\"matches_current\":false")
        val mismatch = PlayerPresentation.from(
            PlayerTestFixtures.state(observation = currentMismatch),
        )
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, mismatch.playbackStatus)
        assertTrue(PlayerDiagnostic.CURRENT_NOT_MATCHED in mismatch.playbackDiagnostics)
        assertFalse(mismatch.controls.pause)

        val stoppedObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"stopped\"")
        val stopped = PlayerPresentation.from(
            PlayerTestFixtures.state(observation = stoppedObservation),
        )
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, stopped.playbackStatus)
        assertTrue(PlayerDiagnostic.ACTUAL_STATE_NOT_PLAYING_OR_PAUSED in stopped.playbackDiagnostics)
        assertFalse(stopped.controls.previous)
        assertFalse(stopped.controls.seek)
    }

    @Test fun staleOrStoppedObservationDoesNotProjectTheCanonicalSongAsCurrentlyPlayingMedia() {
        val staleObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_freshness\":\"fresh\"", "\"actual_freshness\":\"stale\"")
        val stale = PlayerPresentation.from(
            PlayerTestFixtures.state(observation = staleObservation),
        )
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, stale.playbackStatus)
        assertNull(stale.song)
        assertEquals(SongMetadataStatus.NOT_CONFIRMED, stale.songStatus)
        assertEquals(SourceFormatStatus.UNKNOWN, stale.sourceFormat.status)
        assertEquals(ArtworkReferenceStatus.UNKNOWN, stale.artworkReferenceStatus)

        val stoppedObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"stopped\"")
        val stopped = PlayerPresentation.from(
            PlayerTestFixtures.state(observation = stoppedObservation),
        )
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, stopped.playbackStatus)
        assertNull(stopped.song)
        assertEquals(SongMetadataStatus.NOT_CONFIRMED, stopped.songStatus)
    }

    @Test fun ambiguousCurrentOccurrenceAndMismatchedSongMetadataAreNotPresentedAsCurrent() {
        val ambiguousQueue = """{"revision":18,"items":[{"queue_item_id":"occurrence-a","song_id":"song-1","position":0,"source":"MANUAL","playback_context_id":"ctx"},{"queue_item_id":"occurrence-b","song_id":"song-1","position":0,"source":"MANUAL","playback_context_id":"ctx"}]}"""
        val ambiguous = PlayerPresentation.from(
            PlayerTestFixtures.state(queue = ambiguousQueue),
        )
        assertNull(ambiguous.currentOccurrenceId)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, ambiguous.playbackStatus)
        assertTrue(PlayerDiagnostic.CURRENT_OCCURRENCE_AMBIGUOUS in ambiguous.playbackDiagnostics)
        assertNull(ambiguous.song)

        val wrongSong = PlayerTestFixtures.songJson.replace(
            "\"song_id\":\"song-1\"", "\"song_id\":\"other-song\"",
        )
        val mismatched = PlayerPresentation.from(
            PlayerTestFixtures.state(song = wrongSong),
        )
        assertEquals(SongMetadataStatus.OCCURRENCE_MISMATCH, mismatched.songStatus)
        assertTrue(PlayerDiagnostic.CURRENT_SONG_OCCURRENCE_MISMATCH in mismatched.resourceDiagnostics)
        assertNull(mismatched.song)
    }

    @Test fun readErrorLyricsRemainExplicitAndFreshOutputObservationRetainsWebFreshnessSemantics() {
        val readErrorLyrics = PlayerTestFixtures.songJson.replace(
            "\"lyrics_status\":\"available\"", "\"lyrics_status\":\"read_error\"",
        )
        val lyrics = PlayerPresentation.from(
            PlayerTestFixtures.state(song = readErrorLyrics),
        )
        assertEquals(LyricsResourceStatus.READ_ERROR, lyrics.song?.lyricsStatus)
        assertEquals("[00:01.00]Line", lyrics.song?.lyrics)

        val failedOutputObservation = PlayerTestFixtures.webOutputObservationJsonForTest()
            .replace("\"error_code\":null", "\"error_code\":\"OUTPUT_READ_FAILED\"")
        val output = PlayerPresentation.from(
            PlayerTestFixtures.webState(outputObservation = failedOutputObservation),
        )
        assertEquals(OutputObservationStatus.FRESH, output.output.observationStatus)
        assertEquals(DacOutputStatus.ACTIVE, output.output.dacStatus)
        assertEquals("OUTPUT_READ_FAILED", output.output.errorCode)
    }

    @Test fun canonicalWebSnapshotAgreesOnPlaybackFactsAndQueueCapabilities() {
        val result = PlayerPresentation.from(PlayerTestFixtures.webState())
        assertEquals(PlaybackStateValue.PAUSED, result.businessPlaybackState)
        assertEquals(ConfirmedPlaybackStatus.PAUSED, result.playbackStatus)
        assertFalse(result.controls.pause)
        assertTrue(result.controls.resume)
        assertFalse(result.controls.previous)
        assertTrue(result.controls.next)
        assertFalse(result.controls.seek)
        assertEquals(OutputObservationStatus.FRESH, result.output.observationStatus)
        assertEquals(DacOutputStatus.ACTIVE, result.output.dacStatus)
    }

    @Test fun androidProjectionMatchesWebPlayerFactsOracleAcrossCanonicalAndDegradedSnapshots() {
        val cases = JsonConfiguration.json
            .parseToJsonElement(PlayerTestFixtures.webOracleCasesJson())
            .jsonArray

        for (case in cases) {
            val root = case.jsonObject
            val name = root.getValue("name").jsonPrimitive.content
            val writable = root.getValue("writable").jsonPrimitive.boolean
            val expected = root.getValue("expected").jsonObject
            val state = PlayerTestFixtures.webStateFromSnapshot(
                snapshotJson = root.getValue("snapshot").toString(),
                writable = writable,
            )
            val actual = PlayerPresentation.from(state)
            val expectedFreshness = expected.getValue("outputFreshness").jsonPrimitive.content

            assertEquals("$name: bound", expected.getValue("bound").jsonPrimitive.boolean,
                actual.playbackStatus != ConfirmedPlaybackStatus.UNKNOWN)
            assertEquals("$name: song identity", expected["songId"]?.jsonPrimitive?.contentOrNull,
                actual.song?.songId)
            assertEquals("$name: output freshness", expectedFreshness,
                actual.output.observationStatus.name.lowercase(java.util.Locale.ROOT))

            val expectedCapabilities = expected.getValue("capabilities").jsonObject
            assertEquals("$name: pause", expectedCapabilities.getValue("pause").jsonPrimitive.boolean,
                actual.controls.pause)
            assertEquals("$name: resume", expectedCapabilities.getValue("resume").jsonPrimitive.boolean,
                actual.controls.resume)
            assertEquals("$name: seek", expectedCapabilities.getValue("seek").jsonPrimitive.boolean,
                actual.controls.seek)
            assertEquals("$name: next", expectedCapabilities.getValue("next").jsonPrimitive.boolean,
                actual.controls.next)
            assertEquals("$name: previous", expectedCapabilities.getValue("previous").jsonPrimitive.boolean,
                actual.controls.previous)

            val expectedSongFormat = expected["sourceFormat"]
            if (expectedSongFormat == null || expectedSongFormat.toString() == "null") {
                assertNull("$name: current song is not confirmed", actual.song)
            } else {
                val format = expectedSongFormat.jsonObject
                assertNotNull("$name: canonical current song", actual.song)
                assertEquals("$name: source codec", format["codec"]?.jsonPrimitive?.contentOrNull,
                    actual.sourceFormat.codec)
                assertEquals("$name: source bit depth", format["bitDepth"]?.jsonPrimitive?.intOrNull,
                    actual.sourceFormat.bitDepth)
                assertEquals("$name: source rate", format["sampleRateHz"]?.jsonPrimitive?.intOrNull,
                    actual.sourceFormat.sampleRateHz)
                assertEquals("$name: source channels", format["channelCount"]?.jsonPrimitive?.intOrNull,
                    actual.sourceFormat.channelCount)
            }

            val expectedDac = expected["dacOutput"]
            if (expectedDac == null || expectedDac.toString() == "null") {
                assertEquals("$name: absent DAC state is unknown", DacOutputStatus.UNKNOWN, actual.output.dacStatus)
            } else {
                val dac = expectedDac.jsonObject
                val expectedDacStatus = when (expectedFreshness) {
                    "stale" -> DacOutputStatus.STALE
                    "unknown" -> DacOutputStatus.UNKNOWN
                    else -> DacOutputStatus.valueOf(dac.getValue("status").jsonPrimitive.content)
                }
                assertEquals("$name: DAC status", expectedDacStatus, actual.output.dacStatus)
                if (expectedFreshness == "fresh") {
                    assertEquals("$name: DAC format", dac["format"]?.jsonPrimitive?.contentOrNull,
                        actual.output.dacFormat)
                    assertEquals("$name: DAC rate", dac["sampleRate"]?.jsonPrimitive?.intOrNull,
                        actual.output.dacSampleRateHz)
                    assertEquals("$name: DAC depth", dac["bitDepth"]?.jsonPrimitive?.intOrNull,
                        actual.output.dacBitDepth)
                    assertEquals("$name: DAC channels", dac["channels"]?.jsonPrimitive?.intOrNull,
                        actual.output.dacChannels)
                } else {
                    // Android intentionally suppresses old output numbers when their observation is stale/unknown.
                    assertNull("$name: stale DAC format suppressed", actual.output.dacFormat)
                    assertNull("$name: stale DAC rate suppressed", actual.output.dacSampleRateHz)
                    assertNull("$name: stale DAC depth suppressed", actual.output.dacBitDepth)
                    assertNull("$name: stale DAC channels suppressed", actual.output.dacChannels)
                }
            }
        }
    }

    @Test fun queueCapabilitiesFollowQueueOccurrencesAndDoNotRequireControlTargetForNextPrevious() {
        val obs = PlayerTestFixtures.webObservationJsonForTest()

        val withoutTarget = PlayerPresentation.from(
            PlayerTestFixtures.webState(
                observation = obs.replace(
                    "\"control_target\":{\"queue_item_id\":\"item-a\",\"token\":\"fixture-opaque-token\"}",
                    "\"control_target\":null",
                ),
            ),
        )
        assertFalse(withoutTarget.controls.resume)
        assertTrue(withoutTarget.controls.next)
        assertFalse(withoutTarget.controls.previous)

        val oneItemQueue = """{"revision":7,"items":[{"queue_item_id":"item-a","song_id":"song-a","position":0,"source":"MANUAL","playback_context_id":"context-a"}]}"""
        val oneItem = PlayerPresentation.from(
            PlayerTestFixtures.webState(queue = oneItemQueue),
        )
        assertFalse(oneItem.controls.next)
        assertFalse(oneItem.controls.previous)
    }

    @Test fun webSeekCapabilityOnlyRequiresAnActiveTargetAndPositiveDuration() {
        val observation = PlayerTestFixtures.webObservationJsonForTest()
            .replace("\"position_seconds\":37.0", "\"position_seconds\":null")
            .replace("\"duration_seconds\":null", "\"duration_seconds\":180")
        val result = PlayerPresentation.from(
            PlayerTestFixtures.webState(observation = observation),
        )
        assertTrue(result.controls.seek)
    }

    @Test fun outputFreshnessRequiresAStateAndObservationTimestampAndHonorsStateStaleness() {
        val noOutput = PlayerPresentation.from(
            PlayerTestFixtures.webState(output = """{"states":[],"last_request":null}"""),
        )
        assertEquals(OutputObservationStatus.UNKNOWN, noOutput.output.observationStatus)
        assertEquals(DacOutputStatus.UNKNOWN, noOutput.output.dacStatus)

        val noTimestamp = PlayerPresentation.from(
            PlayerTestFixtures.webState(
                outputObservation = PlayerTestFixtures.webOutputObservationJsonForTest()
                    .replace("\"observed_at\":\"2026-10-09T08:00:00Z\"", "\"observed_at\":null"),
            ),
        )
        assertEquals(OutputObservationStatus.UNKNOWN, noTimestamp.output.observationStatus)
        assertEquals(DacOutputStatus.UNKNOWN, noTimestamp.output.dacStatus)

        val staleOutputState = PlayerPresentation.from(
            PlayerTestFixtures.webState(
                output = PlayerTestFixtures.webOutputJsonForTest().replace("\"stale\":false", "\"stale\":true"),
            ),
        )
        assertEquals(OutputObservationStatus.STALE, staleOutputState.output.observationStatus)
        assertEquals(DacOutputStatus.STALE, staleOutputState.output.dacStatus)
    }
}
