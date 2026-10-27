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
}
