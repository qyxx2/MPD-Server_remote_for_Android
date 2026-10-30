package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import org.junit.Assert.*
import org.junit.Test

class PlayerFactsTest {
    @Test fun decodesCanonicalCurrentOccurrenceAndResourceFieldsIndependently() {
        val facts = PlayerFacts.from(PlayerTestFixtures.state())

        assertEquals("occurrence-1", facts.currentOccurrence?.queueItemId)
        assertEquals("song-1", facts.currentSong?.songId)
        assertEquals("occurrence-1", facts.playbackObservation?.boundQueueItemId)
        assertEquals("NAS_DAC", facts.output?.states?.single()?.mode?.name)
        assertNotNull(facts.outputObservation)
        assertTrue(facts.isSnapshotAvailable)
    }

    @Test fun malformedCurrentSongDoesNotEraseQueuePlaybackOrOutputFacts() {
        val state = PlayerTestFixtures.state(song = """{"title":"broken"}""")
        val facts = PlayerFacts.from(state)

        assertNull(facts.currentSong)
        assertEquals(PlayerFacts.SongDecodeStatus.INVALID, facts.songDecodeStatus)
        assertEquals("occurrence-1", facts.currentOccurrence?.queueItemId)
        assertNotNull(facts.playbackObservation)
        assertNotNull(facts.output)
        assertNotNull(facts.outputObservation)
    }

    @Test fun decodesBusinessPlaybackStateFromTheCanonicalWebSnapshotShape() {
        val facts = PlayerFacts.from(PlayerTestFixtures.webState())

        assertEquals("PAUSED", facts.businessPlaybackState?.name)
        assertFalse(facts.businessPlaybackDecodeFailed)
        assertEquals("item-a", facts.currentOccurrence?.queueItemId)
        assertEquals("song-a", facts.currentSong?.songId)
    }

    @Test fun missingSnapshotProducesEmptyFactsWithoutThrowing() {
        val facts = PlayerFacts.from(
            PlayerTestFixtures.state().copy(
                snapshot = null,
                freshness = SnapshotFreshness.UNKNOWN,
            ),
        )

        assertFalse(facts.isSnapshotAvailable)
        assertNull(facts.currentOccurrence)
        assertNull(facts.playbackObservation)
    }

    @Test fun reusesDecodedFactsForTheSameCanonicalSnapshotAcrossFreshnessChanges() {
        val state = PlayerTestFixtures.state()
        val original = PlayerFacts.from(state)
        val stale = PlayerFacts.from(state.copy(freshness = SnapshotFreshness.STALE))

        assertSame("unchanged canonical Queue DTO should be decoded once", original.queue, stale.queue)
        assertSame("unchanged playback observation should be reused", original.playbackObservation, stale.playbackObservation)
        assertSame("unchanged current song DTO should be reused", original.currentSong, stale.currentSong)
        assertEquals(SnapshotFreshness.FRESH, original.snapshotFreshness)
        assertEquals(SnapshotFreshness.STALE, stale.snapshotFreshness)
        assertTrue(original.controlsEligible)
        assertFalse(stale.controlsEligible)
    }

    @Test fun replacesDecodedFactsWhenCanonicalSnapshotIdentityChanges() {
        val state = PlayerTestFixtures.state()
        val original = PlayerFacts.from(state)
        val replacementSnapshot = io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot.from(
            requireNotNull(state.snapshot).wire,
        )
        val replacement = PlayerFacts.from(state.copy(snapshot = replacementSnapshot))

        assertNotSame("a new canonical snapshot must not reuse prior decoded DTOs", original.queue, replacement.queue)
        assertEquals(original.queue?.revision, replacement.queue?.revision)
    }

}
