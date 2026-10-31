package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheIdentityState
import io.github.qyxx2.mpdserver.remote.runtime.ResourceSessionIdentity
import io.github.qyxx2.mpdserver.remote.runtime.ResourceWatermarks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryReadIdentityTest {
    private val session = ResourceSessionIdentity(
        originAddress = "http://server.example:18000",
        connectionGeneration = 4L,
        epoch = "epoch-1",
    )

    private fun state(
        libraryRevision: Long = 11L,
        playlistRevision: Long = 21L,
        isClosed: Boolean = false,
    ) = ResourceCacheIdentityState(
        session = session,
        watermarks = ResourceWatermarks(
            libraryRevision = libraryRevision,
            playlistRevision = playlistRevision,
            historyFingerprint = "history",
        ),
        isFresh = true,
        isClosed = isClosed,
    )

    @Test
    fun libraryOnlyResourcesIgnoreUnrelatedPlaylistRevisionChanges() {
        val identity = LibraryReadIdentity(
            session = session,
            libraryRevision = 11L,
            playlistRevision = null,
            dependsOnPlaylistRevision = false,
            isClosed = false,
        )

        assertTrue(identity.matches(state(playlistRevision = 21L)))
        assertTrue(identity.matches(state(playlistRevision = 22L)))
        assertFalse(identity.matches(state(libraryRevision = 12L, playlistRevision = 22L)))
        assertFalse(identity.matches(state(isClosed = true)))
    }

    @Test
    fun playlistAndFavoriteResourcesRequireTheSamePlaylistRevision() {
        val identity = LibraryReadIdentity(
            session = session,
            libraryRevision = 11L,
            playlistRevision = 21L,
            dependsOnPlaylistRevision = true,
            isClosed = false,
        )

        assertTrue(identity.matches(state(playlistRevision = 21L)))
        assertFalse(identity.matches(state(playlistRevision = 22L)))
        assertFalse(identity.matches(state(libraryRevision = 12L, playlistRevision = 21L)))
    }
}
