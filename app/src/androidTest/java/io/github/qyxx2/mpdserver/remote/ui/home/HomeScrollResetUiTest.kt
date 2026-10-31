package io.github.qyxx2.mpdserver.remote.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScrollResetUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private lateinit var scrollStateHolder: HomeScrollStateHolder

    @Test
    fun leavingHomeForSearchLibraryDetailPlayerOrQueueResetsEveryRail() {
        composeRule.setContent {
            val controller = rememberNavController()
            val entry by controller.currentBackStackEntryAsState()
            val holder = rememberHomeScrollStateHolder()
            HomeScrollResetEffect(entry?.destination?.route, holder)
            SideEffect {
                navController = controller
                scrollStateHolder = holder
            }
            NavHost(navController = controller, startDestination = "home") {
                composable("home") {
                    HomeScreen(state = readyState(), scrollStateHolder = holder)
                }
                listOf("search", "library/album/album-1", "full-screen-player", "queue").forEach { route ->
                    composable(route) {
                        Box(Modifier.fillMaxSize()) { Text(route) }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val routes = listOf("search", "library/album/album-1", "full-screen-player", "queue")
        routes.forEach { route ->
            scrollAllRailsAwayFromStart()
            composeRule.runOnIdle { navController.navigate(route) }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(0, scrollStateHolder.recent.firstVisibleItemIndex)
                assertEquals(0, scrollStateHolder.history.firstVisibleItemIndex)
                assertEquals(0, scrollStateHolder.autoplay.firstVisibleItemIndex)
            }
            composeRule.runOnIdle { navController.popBackStack() }
            composeRule.waitForIdle()
            assertAllRailsAtStart()
        }
    }

    private fun scrollAllRailsAwayFromStart() {
        composeRule.onNodeWithTag("home-rail-recently-updated").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(scrollStateHolder.recent.firstVisibleItemIndex > 0)
            assertEquals(0, scrollStateHolder.history.firstVisibleItemIndex)
            assertEquals(0, scrollStateHolder.autoplay.firstVisibleItemIndex)
        }
        composeRule.onNodeWithTag("home-rail-playback-history").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(scrollStateHolder.recent.firstVisibleItemIndex > 0)
            assertTrue(scrollStateHolder.history.firstVisibleItemIndex > 0)
            assertEquals(0, scrollStateHolder.autoplay.firstVisibleItemIndex)
        }
        composeRule.onNodeWithTag("home-rail-autoplay").performScrollToIndex(6)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(scrollStateHolder.recent.firstVisibleItemIndex > 0)
            assertTrue(scrollStateHolder.history.firstVisibleItemIndex > 0)
            assertTrue(scrollStateHolder.autoplay.firstVisibleItemIndex > 0)
        }
    }

    private fun assertAllRailsAtStart() {
        composeRule.runOnIdle {
            assertEquals(0, scrollStateHolder.recent.firstVisibleItemIndex)
            assertEquals(0, scrollStateHolder.history.firstVisibleItemIndex)
            assertEquals(0, scrollStateHolder.autoplay.firstVisibleItemIndex)
        }
    }

    private fun readyState(): HomeUiState {
        val cards = (0 until 16).map { index ->
            val song = io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto(
                songId = "song-$index",
                title = "歌曲 $index",
                fileUri = "file:///music/$index.flac",
                artists = listOf("艺人"),
                albumId = "album-$index",
                album = "专辑",
                albumArtists = listOf("艺人"),
                trackNumber = null,
                discNumber = null,
                year = null,
                date = null,
                genres = emptyList(),
                tagNames = emptyList(),
                duration = null,
                lyrics = null,
                lyricsFormat = null,
                lyricsSource = null,
                bitDepth = null,
                sampleRateHz = null,
                channelCount = null,
                codec = null,
                metadataStatus = null,
                lastScannedAt = null,
                fileSize = null,
                fileMtimeNs = null,
                contentHash = null,
                lastSeenAt = null,
                artwork = null,
            )
            HomeArtworkCard(
                key = "album:$index",
                kind = HomeCardKind.ALBUM,
                song = song,
                songId = song.songId,
                albumId = song.albumId,
                artworkSongId = null,
                artwork = null,
                isPlayable = true,
                queueItemId = "queue-$index",
            )
        }
        return HomeUiState(
            recentlyUpdated = HomeSectionState.Ready(HomeRecentUpdates(cards)),
            playbackHistory = HomeSectionState.Ready(HomePlaybackHistory(cards, cards.mapNotNull { it.songId })),
            autoplay = HomeSectionState.Ready(HomeAutoplay(cards)),
        )
    }
}
