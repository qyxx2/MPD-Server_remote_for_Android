package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionActionsTest {
    @Test
    fun collectionRequestCopiesSongIdsBeforeCreatingAnIntent() {
        val source = mutableListOf("first", "second")
        val request = CollectionPlaybackRequest(
            sourceType = CollectionPlaybackSource.SONGS,
            songIds = source,
        )
        source.clear()
        assertEquals(listOf("first", "second"), request.songIds)
    }

    @Test
    fun categorySourcesCarryOpaqueOriginalIdsWithoutLocalSongIds() {
        val expected = linkedMapOf(
            LibraryCategory.ALBUMS to "ALBUM",
            LibraryCategory.ARTISTS to "ARTIST",
            LibraryCategory.GENRES to "GENRE",
            LibraryCategory.YEARS to "YEAR",
            LibraryCategory.TAGS to "TAG",
        )
        expected.forEach { (category, sourceType) ->
            val request = CollectionPlaybackRequest.forCategory(category, "opaque/id #1")!!
            assertEquals(sourceType, request.sourceType.name)
            assertEquals("opaque/id #1", request.sourceId)
            assertTrue(request.songIds.isEmpty())
        }
    }

    @Test
    fun mapsSongsPlaylistFavoritesSearchAndLibraryToTheirExactSourceContracts() {
        val allSongs = CollectionPlaybackRequest.forSongs("modified_at", "desc")!!
        assertEquals("LIBRARY", allSongs.sourceType.name)
        assertEquals("modified_at", allSongs.sortBy)
        assertEquals("desc", allSongs.sortOrder)

        val playlist = CollectionPlaybackRequest.forPlaylist("playlist/#1")!!
        assertEquals("PLAYLIST", playlist.sourceType.name)
        assertEquals("playlist/#1", playlist.sourceId)

        val favorites = CollectionPlaybackRequest.forFavorites()
        assertEquals("FAVORITES", favorites.sourceType.name)
        assertNull(favorites.sourceId)

        val search = CollectionPlaybackRequest.forSearch("  Artist / Album  ")!!
        assertEquals("SEARCH", search.sourceType.name)
        assertEquals("Artist / Album", search.query)

        val history = CollectionPlaybackRequest.forSongIds(listOf("a", "b", "a"))!!
        assertEquals("SONGS", history.sourceType.name)
        assertEquals(listOf("a", "b"), history.songIds)
    }

    @Test
    fun collectionWirePayloadContainsOnlySourceAllowedFieldsAndServerRandomizeFlag() {
        val request = CollectionPlaybackRequest.forSongs("title", "asc", randomize = true)!!
        val payload = CollectionPlaybackPayload.encode(request)
        val json = io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json.parseToJsonElement(payload).jsonObject

        assertEquals(
            setOf("source_type", "randomize", "sort_by", "sort_order"),
            json.keys,
        )
        assertEquals("LIBRARY", json.getValue("source_type").jsonPrimitive.content)
        assertEquals("title", json.getValue("sort_by").jsonPrimitive.content)
        assertEquals("asc", json.getValue("sort_order").jsonPrimitive.content)
        assertTrue(json.getValue("randomize").jsonPrimitive.content.toBoolean())
        assertFalse(json.containsKey("song_ids"))
        assertFalse(json.containsKey("random_seed"))
    }

    @Test
    fun libraryShuffleMatchesWebBySendingOnlyLibraryAndRandomize() {
        val request = CollectionPlaybackRequest.forSongs(randomize = true)!!
        val json = JsonConfiguration.json.parseToJsonElement(CollectionPlaybackPayload.encode(request)).jsonObject
        assertEquals(setOf("source_type", "randomize"), json.keys)
        assertEquals("LIBRARY", json.getValue("source_type").jsonPrimitive.content)
        assertTrue(json.getValue("randomize").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun categoryPlaylistFavoriteAndSearchPayloadsDoNotLeakUnrelatedSourceFields() {
        val cases = listOf(
            CollectionPlaybackRequest.forCategory(LibraryCategory.ALBUMS, "album-id")!! to setOf("source_type", "source_id", "randomize"),
            CollectionPlaybackRequest.forPlaylist("playlist-id")!! to setOf("source_type", "source_id", "randomize"),
            CollectionPlaybackRequest.forFavorites() to setOf("source_type", "randomize"),
            CollectionPlaybackRequest.forSearch("term")!! to setOf("source_type", "query", "randomize"),
            CollectionPlaybackRequest.forSongIds(listOf("a", "b"))!! to setOf("source_type", "song_ids", "randomize"),
        )
        cases.forEach { (request, allowedKeys) ->
            val json = io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json
                .parseToJsonElement(CollectionPlaybackPayload.encode(request)).jsonObject
            assertEquals(allowedKeys, json.keys)
        }
    }

    @Test
    fun invalidSourceParametersAndEmptySearchOrSongsAreRejected() {
        assertNull(CollectionPlaybackRequest.forCategory(LibraryCategory.SONGS, "x"))
        assertNull(CollectionPlaybackRequest.forCategory(LibraryCategory.PLAYLISTS, "x"))
        assertNull(CollectionPlaybackRequest.forCategory(LibraryCategory.FAVORITES, "x"))
        assertNull(CollectionPlaybackRequest.forSearch("  "))
        assertNull(CollectionPlaybackRequest.forSongIds(emptyList()))
        assertNull(CollectionPlaybackRequest.forSongs("bad", "asc"))
        assertNull(CollectionPlaybackRequest.forSongs("title", "bad"))
    }

    @Test
    fun submitsOnlyThroughGatewayWithUniqueIntentAndExplicitBody() {
        val h = Harness()
        val actions = h.actions()
        val request = CollectionPlaybackRequest.forCategory(LibraryCategory.ALBUMS, "album/id")!!
        assertEquals(MutationOutcome.Submitted, actions.play(request))
        assertEquals(MutationOutcome.Submitted, actions.play(request.withRandomize(true)))
        assertEquals(h.submitted.last().id, actions.lastSubmittedIntentId)
        assertEquals(listOf("POST", "POST"), h.submitted.map { it.method })
        assertEquals(listOf("/api/playback/collections/play", "/api/playback/collections/play"), h.submitted.map { it.path })
        assertEquals(listOf("album/id"), h.submitted.first().payload!!.let {
            io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json.parseToJsonElement(it)
                .jsonObject.getValue("source_id").jsonPrimitive.content.let(::listOf)
        })
        assertEquals(2, h.submitted.map { it.id }.distinct().size)
    }

    @Test
    fun staleResourceOrGlobalUnresolvedMutationBlocksCollectionSubmission() {
        val staleResource = Harness()
        assertNull(staleResource.actions().play(
            CollectionPlaybackRequest.forFavorites(), resourceFresh = false,
        ))
        assertTrue(staleResource.submitted.isEmpty())

        val unresolved = Harness(
            activities = listOf(
                OperationActivity(
                    intentId = "song:unknown", label = "Song", status = MutationStatus.UNKNOWN,
                    idempotencyKey = "key", origin = ServerOrigin.parse("http://music.example:18000"),
                ),
            ),
        )
        assertNull(unresolved.actions().play(CollectionPlaybackRequest.forFavorites()))
        assertTrue(unresolved.submitted.isEmpty())
    }

    private class Harness(
        activities: List<OperationActivity> = emptyList(),
    ) {
        private var nextId = 0
        val submitted = mutableListOf<MutationIntent>()
        private val liveActivities = MutableStateFlow(activities)

        fun actions() = LibraryCollectionActions(
            gateway = PlaybackMutationGateway(
                stateProvider = { PlayerTestFixtures.state() },
                activities = liveActivities,
                submitter = { intent -> submitted += intent; MutationOutcome.Submitted },
                retryUnknown = { MutationOutcome.Submitted },
                canRetryUnknown = { false },
                idFactory = { "collection-${++nextId}" },
            ),
        )
    }
}
