package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtistListDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtistSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerEntityNavigationRequestTest {
    @Test
    fun artistNavigationUsesTheExistingExactNameLookupAndServerOwnedId() {
        val artists = ArtistListDto(
            items = listOf(
                ArtistSummaryDto(artistId = "artist-1", name = "Alpha", songCount = 2),
                ArtistSummaryDto(artistId = "artist-2", name = "Beta", songCount = 4),
            ),
            count = 2,
        )

        val request = resolveArtistNavigationRequest("Beta", artists)

        assertEquals(
            PlayerEntityNavigationRequest(
                type = PlayerEntityType.ARTIST,
                id = "artist-2",
                displayName = "Beta",
            ),
            request,
        )
        assertNull(resolveArtistNavigationRequest("beta", artists))
        assertNull(resolveArtistNavigationRequest("Missing", artists))
        assertNull(resolveArtistNavigationRequest("", artists))
    }

    @Test
    fun albumNavigationRequestKeepsServerAlbumIdWithoutInventingAnotherIdentifier() {
        val request = PlayerEntityNavigationRequest(
            type = PlayerEntityType.ALBUM,
            id = "album-server-id",
            displayName = "Album",
        )
        assertEquals("album-server-id", request.id)
        assertEquals(PlayerEntityType.ALBUM, request.type)
    }
}
