package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtworkDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.HistoryEventDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.decodeResources
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeProjectionTest {
    private val baseSong: SongDto by lazy {
        JsonConfiguration.json.decodeFromString(
            javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText(),
        )
    }

    private fun song(
        id: String,
        albumId: String? = null,
        album: String? = null,
        title: String = id,
        availability: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        artworkId: String? = null,
        artist: String = "Artist $id",
    ): SongDto = baseSong.copy(
        songId = id,
        fileUri = "music/$id.flac",
        title = title,
        artists = listOf(artist),
        albumId = albumId,
        album = album,
        albumArtists = listOf("Album artist $id"),
        availabilityStatus = availability,
        artwork = artworkId?.let {
            ArtworkDto(
                artworkId = it,
                source = baseSong.artwork!!.source,
                pictureIndex = 0,
                mimeType = "image/jpeg",
                width = 800,
                height = 800,
                contentSha256 = null,
            )
        },
    )

    private fun event(
        id: Long?,
        songId: String,
        startedAt: String = "2026-10-31T10:00:00Z",
    ) = HistoryEventDto(
        historyId = id,
        songId = songId,
        startedAt = startedAt,
        endedAt = null,
        reason = "completed",
        sessionId = "session-1",
    )

    @Test
    fun recentUpdatesGroupsByFirstAlbumAppearanceAndSelectsFirstAvailableArtwork() {
        val newestMissing = song("a-newest", "album-a", "Album A", availability = AvailabilityStatus.MISSING, artworkId = "unavailable-art")
        val albumB = song("b-newest", "album-b", "Album B", artworkId = "art-b")
        val olderAlbumAWithCover = song("a-cover", "album-a", "Album A", artworkId = "art-a")
        val result = HomeProjection.recentlyUpdated(listOf(newestMissing, albumB, olderAlbumAWithCover))

        assertEquals(listOf("album:album-a", "album:album-b"), result.cards.map { it.key })
        assertSame(newestMissing, result.cards[0].song)
        assertEquals("a-cover", result.cards[0].artworkSongId)
        assertEquals("art-a", result.cards[0].artwork?.artworkId)
        assertEquals("b-newest", result.cards[1].artworkSongId)
    }

    @Test
    fun recentUpdatesKeepNullAlbumSongsAsIndependentStableSingletons() {
        val first = song("solo-1", albumId = null)
        val second = song("solo-2", albumId = null)
        val result = HomeProjection.recentlyUpdated(listOf(first, second))

        assertEquals(listOf("song:solo-1", "song:solo-2"), result.cards.map { it.key })
        assertEquals(2, result.cards.size)
        assertTrue(result.cards.all { it.albumId == null })
    }

    @Test
    fun recentUpdatesUseFileUriWhenSongIdIsNull() {
        val row = song("unused").copy(songId = null, fileUri = "music/unindexed.flac")
        val card = HomeProjection.recentlyUpdated(listOf(row)).cards.single()

        assertEquals("file:music/unindexed.flac", card.key)
        assertSame(row, card.song)
        assertFalse("a Song without a server song_id is displayable but not a play target", card.isPlayable)
    }

    @Test
    fun historyKeepsServerOrderDeduplicatesAlbumsAndRetainsOrphanEvents() {
        val newestAlbumTrack = song("album-a-newest", "album-a", "Album A", availability = AvailabilityStatus.MISSING)
        val coverTrack = song("album-a-cover", "album-a", "Album A", artworkId = "album-a-art")
        val otherAlbum = song("album-b-track", "album-b", "Album B", artworkId = "album-b-art")
        val history = listOf(
            event(101, "album-a-newest"),
            event(102, "removed-song"),
            event(103, "album-a-cover", "2026-10-30T09:00:00Z"),
            event(104, "album-b-track", "2026-10-29T09:00:00Z"),
        )

        val result = HomeProjection.playbackHistory(history, listOf(newestAlbumTrack, coverTrack, otherAlbum))

        assertEquals(listOf("album:album-a", "history:102", "album:album-b"), result.cards.map { it.key })
        assertSame(newestAlbumTrack, result.cards[0].song)
        assertEquals("album-a-cover", result.cards[0].artworkSongId)
        assertEquals("removed-song", result.cards[1].songId)
        assertNull(result.cards[1].song)
        assertFalse(result.cards[1].isPlayable)
        assertEquals(listOf("album-a-newest", "removed-song", "album-a-cover", "album-b-track"), result.songIds)
    }

    @Test
    fun historySingletonWithoutHistoryIdHasStableEventIdentityIndependentOfTitle() {
        val history = listOf(event(null, "orphan", "2026-10-30T09:00:00Z"))
        val first = HomeProjection.playbackHistory(history, emptyList()).cards.single()
        val second = HomeProjection.playbackHistory(history, emptyList()).cards.single()
        val afterAnotherEventArrives = HomeProjection.playbackHistory(
            listOf(event(null, "newer-orphan", "2026-10-31T11:00:00Z")) + history,
            emptyList(),
        ).cards.single { it.songId == "orphan" }
        val duplicateEvents = HomeProjection.playbackHistory(history + history, emptyList()).cards

        assertTrue(first.key.startsWith("history:event:"))
        assertEquals(first.key, second.key)
        assertEquals("inserting a newer event must not change an orphan's composite identity", first.key, afterAnotherEventArrives.key)
        assertEquals("identical events without server IDs remain independent singleton cards", 2, duplicateEvents.size)
        assertNotEquals(duplicateEvents[0].key, duplicateEvents[1].key)
        assertNull(first.song)
        assertFalse(first.isPlayable)
    }

    @Test
    fun autoplayUsesCanonicalPlaybackContextAndPreservesEveryOccurrenceInQueuePositionOrder() {
        val snapshotFixture = javaClass.classLoader!!
            .getResource("fixtures/task4/web-player-facts-snapshot.json")!!.readText()
        val json = JsonConfiguration.json
        val root = json.parseToJsonElement(snapshotFixture).jsonObject.toMutableMap()
        root["playback"] = json.parseToJsonElement(
            """{"song_id":"current","state":"PLAYING","playback_context_id":"ctx-current","position_seconds":12.0,"autoplay_enabled":true,"updated_at":"2026-10-31T10:00:00Z"}""",
        )
        root["queue"] = json.parseToJsonElement(
            """{"revision":19,"items":[
                {"queue_item_id":"occurrence-late","song_id":"duplicate-song","position":4,"source":"AUTOPLAY","playback_context_id":"ctx-current"},
                {"queue_item_id":"manual","song_id":"manual-song","position":2,"source":"MANUAL","playback_context_id":"ctx-current"},
                {"queue_item_id":"current","song_id":"current","position":0,"source":"AUTOPLAY","playback_context_id":"ctx-current"},
                {"queue_item_id":"wrong-context","song_id":"wrong-song","position":1,"source":"AUTOPLAY","playback_context_id":"ctx-old"},
                {"queue_item_id":"null-context","song_id":"null-song","position":3,"source":"AUTOPLAY","playback_context_id":null},
                {"queue_item_id":"occurrence-first","song_id":"duplicate-song","position":1,"source":"AUTOPLAY","playback_context_id":"ctx-current"},
                {"queue_item_id":"played","song_id":"played-song","position":-1,"source":"AUTOPLAY","playback_context_id":"ctx-current"}
            ]}""",
        )
        val dto = json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root))
        val snapshot = FullStateSnapshot.from(dto)
        val decoded = dto.decodeResources()
        assertEquals("ctx-current", decoded.playback.playbackContextId)

        val duplicateSong = song("duplicate-song", "album-duplicate", "Same Album", artworkId = "duplicate-art")
        val result = HomeProjection.autoplay(snapshot, listOf(duplicateSong))

        assertEquals(listOf("occurrence-first", "occurrence-late"), result.cards.map { it.queueItemId })
        assertEquals(listOf("queue:occurrence-first", "queue:occurrence-late"), result.cards.map { it.key })
        assertEquals(listOf("duplicate-song", "duplicate-song"), result.cards.map { it.songId })
        assertEquals(listOf("duplicate-art", "duplicate-art"), result.cards.map { it.artwork?.artworkId })
    }

    @Test
    fun autoplayDisabledOrMissingPlaybackContextProducesNoCandidates() {
        val snapshotFixture = javaClass.classLoader!!
            .getResource("fixtures/task4/web-player-facts-snapshot.json")!!.readText()
        val json = JsonConfiguration.json
        val playbackCases = listOf(
            """{"song_id":"song-a","state":"PLAYING","playback_context_id":null,"position_seconds":5.0,"autoplay_enabled":true,"updated_at":"2026-10-31T10:00:00Z"}""",
            """{"song_id":"song-a","state":"PLAYING","playback_context_id":"context-a","position_seconds":5.0,"autoplay_enabled":false,"updated_at":"2026-10-31T10:00:00Z"}""",
        )
        playbackCases.forEach { playback ->
            val root = json.parseToJsonElement(snapshotFixture).jsonObject.toMutableMap()
            root["playback"] = json.parseToJsonElement(playback)
            val snapshot = FullStateSnapshot.from(json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root)))
            assertTrue(HomeProjection.autoplay(snapshot, emptyList()).cards.isEmpty())
        }
    }

    @Test
    fun autoplayRetainsMissingSongAsAnOccurrencePlaceholderWithoutBorrowingNeighborArtwork() {
        val snapshotFixture = javaClass.classLoader!!
            .getResource("fixtures/task4/web-player-facts-snapshot.json")!!.readText()
        val json = JsonConfiguration.json
        val root = json.parseToJsonElement(snapshotFixture).jsonObject.toMutableMap()
        root["queue"] = json.parseToJsonElement(
            """{"revision":23,"items":[{"queue_item_id":"missing-occurrence","song_id":"not-in-library","position":1,"source":"AUTOPLAY","playback_context_id":"context-a"}]}""",
        )
        val snapshot = FullStateSnapshot.from(json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root)))
        val neighbor = song("neighbor", "other-album", "Other Album", artworkId = "neighbor-art")

        val card = HomeProjection.autoplay(snapshot, listOf(neighbor)).cards.single()

        assertEquals("queue:missing-occurrence", card.key)
        assertEquals("not-in-library", card.songId)
        assertNull(card.song)
        assertNull(card.artwork)
        assertNull(card.artworkSongId)
        assertFalse(card.isPlayable)
    }
}
