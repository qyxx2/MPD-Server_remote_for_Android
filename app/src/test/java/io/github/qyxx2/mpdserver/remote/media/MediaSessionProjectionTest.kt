package io.github.qyxx2.mpdserver.remote.media

import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import org.junit.Assert.*
import org.junit.Test

class MediaSessionProjectionTest {
    private val origin = "http://music.example:18000"

    @Test
    fun confirmedPlayingProjectsCurrentOccurrenceMetadataAndOnlyAvailableActions() {
        val queue = queueWithHistoryCurrentAndNext()
        val state = PlayerTestFixtures.state(queue = queue)

        val projected = MediaSessionProjection.project(
            state = state,
            originAddress = origin,
            previousConfirmed = null,
            commandActions = MediaSessionCommandAvailability.from(PlayerPresentation.from(state).controls),
        )

        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, projected.disposition)
        assertEquals("occurrence-1", projected.metadata?.mediaId)
        assertEquals("Example", projected.metadata?.title)
        assertEquals("Artist A, Artist B", projected.metadata?.artist)
        assertEquals("Album", projected.metadata?.album)
        assertEquals(123_500L, projected.metadata?.durationMillis)
        assertEquals(MediaSessionPlaybackState.PLAYING, projected.playbackState)
        assertEquals(12_500L, projected.positionMillis)
        assertEquals(
            MediaSessionCommandAvailability(pause = true, previous = true, next = true),
            projected.actions,
        )
        assertNotNull(projected.confirmedIdentity)
    }

    @Test
    fun businessPlaybackStoppedAloneDoesNotWithdrawFreshActualPlayback() {
        val businessStopped = """{"song_id":"song-1","state":"STOPPED","playback_context_id":null,"position_seconds":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}"""
        val state = PlayerTestFixtures.state(playback = businessStopped)
        val projected = project(state)

        assertEquals(ConfirmedPlaybackStatus.PLAYING, PlayerPresentation.from(state).playbackStatus)
        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, projected.disposition)
        assertEquals(MediaSessionPlaybackState.PLAYING, projected.playbackState)
        assertEquals("occurrence-1", projected.metadata?.mediaId)
    }

    @Test
    fun queueEdgesExposeOnlyTheMatchingPreviousOrNextAction() {
        val previousOnlyQueue = """{"revision":18,"items":[{"queue_item_id":"previous","song_id":"song-0","position":-1,"source":"MANUAL"},{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"}]}"""
        val nextOnlyQueue = """{"revision":18,"items":[{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"next","song_id":"song-2","position":1,"source":"MANUAL"}]}"""
        val previousOnly = project(PlayerTestFixtures.state(queue = previousOnlyQueue))
        val nextOnly = project(PlayerTestFixtures.state(queue = nextOnlyQueue))

        assertTrue(previousOnly.actions.previous)
        assertFalse(previousOnly.actions.next)
        assertTrue(previousOnly.actions.pause)
        assertTrue(nextOnly.actions.next)
        assertFalse(nextOnly.actions.previous)
        assertTrue(nextOnly.actions.pause)
    }

    @Test
    fun repeatedSongUsesTheConfirmedQueueOccurrenceAsSystemMediaIdentity() {
        val repeatedQueue = """{"revision":21,"items":[{"queue_item_id":"current-occurrence","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"later-same-song","song_id":"song-1","position":1,"source":"AUTOPLAY"}]}"""
        val observation = PlayerTestFixtures.observationJson
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"current-occurrence\"")
            .replace("\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}", "\"control_target\":{\"queue_item_id\":\"current-occurrence\",\"token\":\"opaque-token\"}")
        val state = PlayerTestFixtures.state(queue = repeatedQueue, observation = observation)
        val result = project(state)

        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, result.disposition)
        assertEquals("current-occurrence", result.metadata?.mediaId)
        assertEquals("song-1", result.confirmedIdentity?.songId)
        assertNotEquals("later-same-song", result.metadata?.mediaId)
    }

    @Test
    fun confirmedPausedProjectsResumeInsteadOfPauseAndPreservesServerTargetCapability() {
        val observation = PlayerTestFixtures.observationJson.replace(
            "\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"",
        )
        val state = PlayerTestFixtures.state(observation = observation)

        val projected = MediaSessionProjection.project(
            state,
            origin,
            null,
            MediaSessionCommandAvailability.from(PlayerPresentation.from(state).controls),
        )

        assertEquals(ConfirmedPlaybackStatus.PAUSED, PlayerPresentation.from(state).playbackStatus)
        assertEquals(MediaSessionPlaybackState.PAUSED, projected.playbackState)
        assertFalse(projected.actions.pause)
        assertTrue(projected.actions.resume)
        assertFalse(projected.actions.previous)
        assertFalse(projected.actions.next)
    }

    @Test
    fun firstStaleSnapshotDoesNotPublishAndExistingSessionRetainsMetadataWithoutStateOrActions() {
        val confirmedState = PlayerTestFixtures.state()
        val confirmed = project(confirmedState)
        val stale = PlayerTestFixtures.state(
            freshness = SnapshotFreshness.STALE,
        )

        val firstStale = MediaSessionProjection.project(
            stale, origin, null, MediaSessionCommandAvailability.NONE,
        )
        val retained = MediaSessionProjection.project(
            stale, origin, requireNotNull(confirmed.confirmedIdentity), MediaSessionCommandAvailability.NONE,
        )

        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, firstStale.disposition)
        assertNull(firstStale.metadata)
        assertEquals(MediaSessionProjection.Disposition.RETAINED, retained.disposition)
        assertEquals(confirmed.metadata, retained.metadata)
        assertEquals(MediaSessionPlaybackState.NONE, retained.playbackState)
        assertEquals(MediaSessionCommandAvailability.NONE, retained.actions)
        assertNull(retained.positionMillis)
    }

    @Test
    fun disconnectedSnapshotRetainsOnlySameOriginAndSameEpochMetadata() {
        val confirmed = project(PlayerTestFixtures.state())
        val disconnected = PlayerTestFixtures.state(
            freshness = SnapshotFreshness.STALE,
            connectionStatus = io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus.DISCONNECTED,
        )
        val retained = MediaSessionProjection.project(
            disconnected, origin, requireNotNull(confirmed.confirmedIdentity), MediaSessionCommandAvailability.NONE,
        )
        val changedOrigin = MediaSessionProjection.project(
            disconnected, "http://other.example:18000",
            requireNotNull(confirmed.confirmedIdentity), MediaSessionCommandAvailability.NONE,
        )

        assertEquals(MediaSessionProjection.Disposition.RETAINED, retained.disposition)
        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, changedOrigin.disposition)

        val changedEpochSnapshot = requireNotNull(disconnected.snapshot).wire.copy(epoch = "epoch-new")
        val changedEpoch = disconnected.copy(snapshot = FullStateSnapshot.from(changedEpochSnapshot))
        val epochResult = MediaSessionProjection.project(
            changedEpoch, origin, requireNotNull(confirmed.confirmedIdentity), MediaSessionCommandAvailability.NONE,
        )
        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, epochResult.disposition)
    }

    @Test
    fun unconfirmedSnapshotCannotCreateFirstSession() {
        val staleSnapshot = PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE)
        val staleObservation = PlayerTestFixtures.observationJson.replace(
            "\"actual_freshness\":\"fresh\"", "\"actual_freshness\":\"stale\"",
        )
        val actualStopped = PlayerTestFixtures.observationJson.replace(
            "\"actual_state\":\"playing\"", "\"actual_state\":\"stopped\"",
        )
        val unbound = PlayerTestFixtures.observationJson
            .replace("\"matches_current\":true", "\"matches_current\":false")
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":null")
        val noControlTarget = PlayerTestFixtures.observationJson.replace(
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
            "\"control_target\":null",
        )
        val unconfirmedSync = PlayerTestFixtures.observationJson.replace(
            "\"sync_status\":\"CONFIRMED\"",
            "\"sync_status\":\"UNBOUND\"",
        )
        val noSong = PlayerTestFixtures.state(song = null)
        val mismatchedSong = PlayerTestFixtures.songJson.replace(
            "\"song_id\":\"song-1\"", "\"song_id\":\"another-song\"",
        )
        val cases = listOf(
            staleSnapshot,
            PlayerTestFixtures.state(observation = staleObservation),
            PlayerTestFixtures.state(observation = actualStopped),
            PlayerTestFixtures.state(observation = unbound),
            PlayerTestFixtures.state(observation = unconfirmedSync),
            noSong,
            PlayerTestFixtures.state(song = """{"not_a_song":true}"""),
            PlayerTestFixtures.state(song = mismatchedSong),
        )

        cases.forEachIndexed { index, state ->
            val result = MediaSessionProjection.project(
                state, origin, null,
                MediaSessionCommandAvailability.from(PlayerPresentation.from(state).controls),
            )
            assertEquals("unconfirmed fixture $index", MediaSessionProjection.Disposition.WITHDRAWN, result.disposition)
            assertNull("unconfirmed fixture $index must not publish metadata", result.metadata)
            assertEquals(MediaSessionCommandAvailability.NONE, result.actions)
        }
    }

    @Test
    fun missingResumeTargetDoesNotHideConfirmedTrackButDisablesResumeAction() {
        val pausedWithoutTarget = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
            .replace(
                "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
                "\"control_target\":null",
            )
        val state = PlayerTestFixtures.state(observation = pausedWithoutTarget)
        val result = project(state)

        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, result.disposition)
        assertEquals(MediaSessionPlaybackState.PAUSED, result.playbackState)
        assertNotNull(result.metadata)
        assertFalse(result.actions.resume)
        assertFalse(result.actions.pause)
    }

    @Test
    fun generationChangeAtSameOriginRetainsMetadataButRevokesActionsUntilFreshSnapshot() {
        val confirmed = project(PlayerTestFixtures.state())
        val disconnectedGeneration = PlayerTestFixtures.state(
            freshness = SnapshotFreshness.STALE,
            connectionGeneration = 2,
            snapshotConnectionGeneration = 1,
        )
        val result = MediaSessionProjection.project(
            disconnectedGeneration, origin, requireNotNull(confirmed.confirmedIdentity),
            MediaSessionCommandAvailability.NONE,
        )
        assertEquals(MediaSessionProjection.Disposition.RETAINED, result.disposition)
        assertEquals(confirmed.metadata, result.metadata)
        assertEquals(MediaSessionPlaybackState.NONE, result.playbackState)
        assertEquals(MediaSessionCommandAvailability.NONE, result.actions)
    }

    @Test
    fun missingSnapshotRetainsLastConfirmedMetadataOnlyWithinTheSameOrigin() {
        val confirmed = project(PlayerTestFixtures.state())
        val noSnapshot = io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState()
        val retained = MediaSessionProjection.project(
            noSnapshot, origin, requireNotNull(confirmed.confirmedIdentity), MediaSessionCommandAvailability.NONE,
        )
        assertEquals(MediaSessionProjection.Disposition.RETAINED, retained.disposition)
        assertEquals(confirmed.metadata, retained.metadata)
        assertEquals(MediaSessionPlaybackState.NONE, retained.playbackState)
        assertEquals(MediaSessionCommandAvailability.NONE, retained.actions)
    }

    @Test
    fun freshCurrentFromNewOriginOrEpochCanReplaceOldSessionImmediately() {
        val old = project(PlayerTestFixtures.state())
        val last = requireNotNull(old.confirmedIdentity)
        val newOrigin = MediaSessionProjection.project(
            PlayerTestFixtures.state(), "http://new.example:18000", last,
            MediaSessionCommandAvailability.from(PlayerPresentation.from(PlayerTestFixtures.state()).controls),
        )
        val original = PlayerTestFixtures.state()
        val newEpochWire = requireNotNull(original.snapshot).wire.copy(epoch = "epoch-2")
        val newEpochState = original.copy(snapshot = FullStateSnapshot.from(newEpochWire))
        val newEpoch = MediaSessionProjection.project(
            newEpochState, origin, last,
            MediaSessionCommandAvailability.from(PlayerPresentation.from(newEpochState).controls),
        )
        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, newOrigin.disposition)
        assertEquals("http://new.example:18000", newOrigin.confirmedIdentity?.originAddress)
        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, newEpoch.disposition)
        assertEquals("epoch-2", newEpoch.confirmedIdentity?.epoch)
    }

    @Test
    fun freshReplacementOccurrenceCanReplaceOldMetadataInOneProjection() {
        val old = project(PlayerTestFixtures.state())
        val newQueue = """{"revision":19,"items":[{"queue_item_id":"occurrence-new","song_id":"song-2","position":0,"source":"MANUAL"}]}"""
        val newObservation = PlayerTestFixtures.observationJson
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"occurrence-new\"")
            .replace("\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}", "\"control_target\":{\"queue_item_id\":\"occurrence-new\",\"token\":\"new-token\"}")
        val newSong = PlayerTestFixtures.songJson.replace(
            "\"song_id\":\"song-1\"", "\"song_id\":\"song-2\"",
        ).replace("\"title\":\"Example\"", "\"title\":\"New Track\"")
        val newState = PlayerTestFixtures.state(queue = newQueue, observation = newObservation, song = newSong)
        val replacement = MediaSessionProjection.project(
            newState, origin, requireNotNull(old.confirmedIdentity),
            MediaSessionCommandAvailability.from(PlayerPresentation.from(newState).controls),
        )
        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, replacement.disposition)
        assertEquals("occurrence-new", replacement.metadata?.mediaId)
        assertEquals("New Track", replacement.metadata?.title)
    }

    @Test
    fun currentOccurrenceMissingOrAmbiguousWithdrawsSession() {
        val confirmed = project(PlayerTestFixtures.state())
        val last = requireNotNull(confirmed.confirmedIdentity)

        val noCurrentQueue = """{"revision":18,"items":[{"queue_item_id":"played","song_id":"song-0","position":-1,"source":"MANUAL"}]}"""
        val missing = PlayerTestFixtures.state(queue = noCurrentQueue)
        val missingResult = MediaSessionProjection.project(
            missing, origin, last, MediaSessionCommandAvailability.NONE,
        )

        val ambiguousQueue = """{"revision":18,"items":[{"queue_item_id":"one","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"two","song_id":"song-1","position":0,"source":"MANUAL"}]}"""
        val ambiguous = PlayerTestFixtures.state(queue = ambiguousQueue)
        val ambiguousResult = MediaSessionProjection.project(
            ambiguous, origin, last, MediaSessionCommandAvailability.NONE,
        )

        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, missingResult.disposition)
        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, ambiguousResult.disposition)
    }

    @Test
    fun explicitStopGenerationAdvanceWithdrawsButActualStoppedAloneDoesNot() {
        val confirmed = project(PlayerTestFixtures.state())
        val last = requireNotNull(confirmed.confirmedIdentity)

        val stoppedObservation = PlayerTestFixtures.observationJson.replace(
            "\"actual_state\":\"playing\"", "\"actual_state\":\"stopped\"",
        )
        val actualStopped = PlayerTestFixtures.state(observation = stoppedObservation)
        val retainedStopped = MediaSessionProjection.project(
            actualStopped, origin, last, MediaSessionCommandAvailability.NONE,
        )

        val stoppedWire = requireNotNull(actualStopped.snapshot).wire.copy(
            explicitStopGeneration = 1,
            sequence = 11,
        )
        val explicitStop = actualStopped.copy(snapshot = FullStateSnapshot.from(stoppedWire))
        val withdrawn = MediaSessionProjection.project(
            explicitStop, origin, last, MediaSessionCommandAvailability.NONE,
        )

        assertEquals(MediaSessionProjection.Disposition.RETAINED, retainedStopped.disposition)
        assertEquals(MediaSessionPlaybackState.NONE, retainedStopped.playbackState)
        assertEquals(MediaSessionCommandAvailability.NONE, retainedStopped.actions)
        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, withdrawn.disposition)
    }

    @Test
    fun clearingPendingQueueDoesNotWithdrawTheStillConfirmedCurrentOccurrence() {
        val confirmed = project(PlayerTestFixtures.state(queue = queueWithHistoryCurrentAndNext()))
        val currentOnlyQueue = """{"revision":19,"items":[{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"}]}"""
        val afterPendingClear = PlayerTestFixtures.state(queue = currentOnlyQueue)
        val result = MediaSessionProjection.project(
            afterPendingClear,
            origin,
            requireNotNull(confirmed.confirmedIdentity),
            MediaSessionCommandAvailability.from(PlayerPresentation.from(afterPendingClear).controls),
        )

        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, result.disposition)
        assertEquals("occurrence-1", result.metadata?.mediaId)
        assertEquals(MediaSessionPlaybackState.PLAYING, result.playbackState)
        assertTrue(result.actions.pause)
        assertFalse(result.actions.previous)
        assertFalse(result.actions.next)
    }

    @Test
    fun unboundObservationRetainsLastConfirmedMetadataButDisablesStateAndActions() {
        val confirmed = project(PlayerTestFixtures.state())
        val last = requireNotNull(confirmed.confirmedIdentity)
        val unbound = PlayerTestFixtures.observationJson
            .replace("\"matches_current\":true", "\"matches_current\":false")
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":null")
        val state = PlayerTestFixtures.state(observation = unbound)

        val result = MediaSessionProjection.project(
            state, origin, last, MediaSessionCommandAvailability.NONE,
        )

        assertEquals(MediaSessionProjection.Disposition.RETAINED, result.disposition)
        assertEquals(confirmed.metadata, result.metadata)
        assertEquals(MediaSessionPlaybackState.NONE, result.playbackState)
        assertEquals(MediaSessionCommandAvailability.NONE, result.actions)
    }

    @Test
    fun replacedCurrentOccurrenceNeverRetainsThePreviousTrack() {
        val confirmed = project(PlayerTestFixtures.state())
        val replacementQueue = """{"revision":19,"items":[{"queue_item_id":"occurrence-new","song_id":"song-2","position":0,"source":"MANUAL"}]}"""
        val replaced = PlayerTestFixtures.state(queue = replacementQueue)
        val result = MediaSessionProjection.project(
            replaced, origin, requireNotNull(confirmed.confirmedIdentity), MediaSessionCommandAvailability.NONE,
        )
        assertEquals(MediaSessionProjection.Disposition.WITHDRAWN, result.disposition)
        assertNull(result.metadata)
    }

    @Test
    fun playerCommandLockRemovesActionsWithoutChangingConfirmedPlaybackState() {
        val state = PlayerTestFixtures.state()
        val locked = MediaSessionProjection.project(
            state, origin, null, MediaSessionCommandAvailability.NONE,
        )

        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, locked.disposition)
        assertEquals(MediaSessionPlaybackState.PLAYING, locked.playbackState)
        assertEquals(MediaSessionCommandAvailability.NONE, locked.actions)
    }

    @Test
    fun incompleteSongFieldsAreOmittedAndInvalidDurationIsNotPublished() {
        val song = PlayerTestFixtures.songJson
            .replace("\"title\":\"Example\"", "\"title\":\"   \"")
            .replace("\"artists\":[\"Artist A\",\"Artist B\"]", "\"artists\":[]")
            .replace("\"album\":\"Album\"", "\"album\":\" \"")
            .replace("\"duration\":123.5", "\"duration\":-1.0")
        val state = PlayerTestFixtures.state(song = song)
        val result = project(state)

        assertEquals(MediaSessionProjection.Disposition.CONFIRMED, result.disposition)
        assertNull(result.metadata?.title)
        assertNull(result.metadata?.artist)
        assertNull(result.metadata?.album)
        assertNull(result.metadata?.durationMillis)
        assertEquals("occurrence-1", result.metadata?.mediaId)
    }

    private fun project(state: io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState): MediaSessionProjection.Result =
        MediaSessionProjection.project(
            state = state,
            originAddress = origin,
            previousConfirmed = null,
            commandActions = MediaSessionCommandAvailability.from(PlayerPresentation.from(state).controls),
        )

    private fun queueWithHistoryCurrentAndNext() =
        """{"revision":18,"items":[{"queue_item_id":"played-1","song_id":"song-0","position":-1,"source":"MANUAL"},{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"next-1","song_id":"song-2","position":1,"source":"AUTOPLAY"}]}"""
}
