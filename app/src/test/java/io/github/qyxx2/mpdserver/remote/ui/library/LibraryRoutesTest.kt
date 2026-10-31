package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.ui.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRoutesTest {
    @Test
    fun categoryRoutesCoverAllEightLibraryEntriesInContractOrder() {
        assertEquals(
            listOf(
                "library/songs",
                "library/playlists",
                "library/artists",
                "library/albums",
                "library/genres",
                "library/years",
                "library/tags",
                "library/favorites",
            ),
            LibraryCategory.entries.map(LibraryRoutes::category),
        )
    }

    @Test
    fun favoritesUsesTheSameSimpleGlyphTreatmentAsOtherLibraryRows() {
        assertEquals("☆", LibraryCategory.FAVORITES.glyph)
    }

    @Test
    fun detailRouteEncodesAndDecodesOpaqueServerIdsAsOnePathSegment() {
        val sourceId = "周杰伦 & #1 + 50%?曲目"
        val route = LibraryRoutes.detail(LibraryCategory.ALBUMS, sourceId)

        assertTrue(route.startsWith("library/albums/"))
        assertEquals(sourceId, LibraryRoutes.decodePathSegment(route.substringAfterLast('/')))
        assertTrue("A slash inside a server ID must not create another route segment", route.count { it == '/' } == 2)
    }

    @Test
    fun everyEntityCategoryBuildsItsExactDetailRouteAndOnlySongsFavoritesAreRejected() {
        listOf(
            LibraryCategory.PLAYLISTS to "library/playlists/source-1",
            LibraryCategory.ARTISTS to "library/artists/source-1",
            LibraryCategory.ALBUMS to "library/albums/source-1",
            LibraryCategory.GENRES to "library/genres/source-1",
            LibraryCategory.YEARS to "library/years/source-1",
            LibraryCategory.TAGS to "library/tags/source-1",
        ).forEach { (category, expectedRoute) ->
            assertEquals(expectedRoute, LibraryRoutes.detail(category, "source-1"))
        }
        listOf(LibraryCategory.SONGS, LibraryCategory.FAVORITES).forEach { category ->
            assertThrows(IllegalArgumentException::class.java) {
                LibraryRoutes.detail(category, "not-a-detail")
            }
        }
    }

    @Test
    fun unsafeOrBlankIdsAreRejectedBeforeBuildingNavigationRoutes() {
        listOf("", " ", ".", "..", "../other", "a/b", "a\\b").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                LibraryRoutes.detail(LibraryCategory.ALBUMS, id)
            }
        }
    }

    @Test
    fun everyNestedLibraryRouteSelectsLibraryRootWithoutChangingOtherRoots() {
        listOf(
            "library",
            "library/songs",
            "library/albums",
            "library/albums/album%2Fone",
            "library/playlists/playlist-1",
        ).forEach { route ->
            assertEquals("library", AppDestination.rootRouteFor(route))
        }
        assertEquals("home", AppDestination.rootRouteFor("home"))
        assertEquals("search", AppDestination.rootRouteFor("search"))
        assertEquals("settings", AppDestination.rootRouteFor("settings"))
        assertNull(AppDestination.rootRouteFor("full-screen-player"))
        assertNull(AppDestination.rootRouteFor("queue"))
        assertNull(AppDestination.rootRouteFor(null))
    }
}
