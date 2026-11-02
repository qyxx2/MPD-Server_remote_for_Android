package io.github.qyxx2.mpdserver.remote.media

import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtworkIdentityGuardTest {
    private val origin = "http://music.example:18000"

    private fun confirmedIdentity() = run {
        val state = PlayerTestFixtures.state()
        val projection = MediaSessionProjection.project(
            state,
            origin,
            null,
            MediaSessionCommandAvailability.from(PlayerPresentation.from(state).controls),
        )
        assertNotNull(projection.confirmedIdentity)
        requireNotNull(projection.confirmedIdentity)
    }

    @Test
    fun onlyCurrentFreshOriginGenerationEpochLibraryOccurrenceSongAndArtworkCanAcceptBitmap() {
        val expected = confirmedIdentity()
        val song = PlayerTestFixtures.songJson
        val state = PlayerTestFixtures.state(song = song)

        assertTrue(MediaArtworkIdentityGuard.isCurrent(state, origin, expected))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(state, "http://other.example:18000", expected))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(state.copy(connectionGeneration = 2), origin, expected))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(state.copy(snapshotConnectionGeneration = 2), origin, expected))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(
            state.copy(snapshot = FullStateSnapshot.from(requireNotNull(state.snapshot).wire.copy(epoch = "new-epoch"))),
            origin,
            expected,
        ))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(
            state.copy(snapshot = FullStateSnapshot.from(requireNotNull(state.snapshot).wire.copy(
                revisions = StateRevisionsDto(library = expected.libraryRevision + 1, playlist = 3),
            ))),
            origin,
            expected,
        ))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(
            state.copy(snapshot = FullStateSnapshot.from(requireNotNull(state.snapshot).wire.copy(
                explicitStopGeneration = expected.explicitStopGeneration + 1,
            ))),
            origin,
            expected,
        ))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(
            PlayerTestFixtures.state(song = song.replace("art-1", "art-other")),
            origin,
            expected,
        ))
    }

    @Test
    fun staleOrAmbiguousCurrentCannotReceiveLateArtwork() {
        val expected = confirmedIdentity()
        val stale = PlayerTestFixtures.state(
            freshness = io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness.STALE,
        )
        val ambiguousQueue = """{"revision":18,"items":[{"queue_item_id":"one","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"two","song_id":"song-1","position":0,"source":"MANUAL"}]}"""
        val changedOccurrenceQueue = """{"revision":19,"items":[{"queue_item_id":"new-occurrence","song_id":"song-1","position":0,"source":"MANUAL"}]}"""
        val changedOccurrenceObservation = PlayerTestFixtures.observationJson
            .replace("\"bound_queue_item_id\":\"occurrence-1\"", "\"bound_queue_item_id\":\"new-occurrence\"")
            .replace(
                "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
                "\"control_target\":{\"queue_item_id\":\"new-occurrence\",\"token\":\"new-token\"}",
            )
        assertFalse(MediaArtworkIdentityGuard.isCurrent(stale, origin, expected))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(
            PlayerTestFixtures.state(queue = ambiguousQueue), origin, expected,
        ))
        assertFalse(MediaArtworkIdentityGuard.isCurrent(
            PlayerTestFixtures.state(
                queue = changedOccurrenceQueue,
                observation = changedOccurrenceObservation,
            ),
            origin,
            expected,
        ))
    }
}
