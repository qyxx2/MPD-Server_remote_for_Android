package io.github.qyxx2.mpdserver.remote.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sectionTags = listOf(
        "home-section-recently-updated",
        "home-section-playback-history",
        "home-section-autoplay",
    )
    private val railTags = listOf(
        "home-rail-recently-updated",
        "home-rail-playback-history",
        "home-rail-autoplay",
    )

    @Test
    fun rendersThreeIndependentEqualHeightHorizontalRailsInContractOrder() {
        composeRule.setContent {
            HomeScreen(
                state = readyState(),
                scrollStateHolder = rememberHomeScrollStateHolder(),
            )
        }
        composeRule.waitForIdle()

        listOf("最近更新", "播放历史", "猜你喜欢").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        sectionTags.forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithContentDescription(
            "猜你喜欢，专辑 0，艺人 0，队列项 queue-item-0",
        ).assertIsDisplayed()
        railTags.forEach { tag ->
            val rail = composeRule.onNodeWithTag(tag)
            rail.assertIsDisplayed()
            assertTrue(
                "Each Home rail must expose horizontal scrolling",
                rail.fetchSemanticsNode().config.contains(SemanticsProperties.HorizontalScrollAxisRange),
            )
        }

        val sectionBounds = sectionTags.map {
            composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        listOf(
            "最近更新" to "album:album-0",
            "播放历史" to "album:album-0",
            "猜你喜欢" to "album:album-0",
        ).forEachIndexed { index, (sectionTitle, cardKey) ->
            val railBounds = composeRule.onNodeWithTag(railTags[index]).fetchSemanticsNode().boundsInRoot
            val cardBounds = composeRule.onNodeWithTag(
                "home-card-${sectionTitle.hashCode()}-${cardKey.hashCode()}",
            ).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "${sectionTitle} card metadata must fit inside its rail viewport",
                cardBounds.bottom <= railBounds.bottom + 1f,
            )
        }
        assertTrue(sectionBounds.zipWithNext().all { (a, b) -> a.top < b.top })
        val heights = sectionBounds.map { it.height }
        assertTrue(
            "The three sections must receive equal height: $heights",
            heights.maxOrNull()!! - heights.minOrNull()!! <= 1.5f,
        )

        val rootConfig = composeRule.onNodeWithTag("home-screen").fetchSemanticsNode().config
        assertFalse(
            "Home must not add a vertical scrolling parent",
            rootConfig.contains(SemanticsProperties.VerticalScrollAxisRange),
        )

        railTags.forEach { composeRule.onNodeWithTag(it).performScrollToIndex(5) }
        composeRule.waitForIdle()
    }

    @Test
    fun loadingEmptyAndFailureStatesKeepAllThreeRailsMounted() {
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(
                    recentlyUpdated = HomeSectionState.Loading,
                    playbackHistory = HomeSectionState.Empty,
                    autoplay = HomeSectionState.Failed("服务暂不可用"),
                ),
                scrollStateHolder = rememberHomeScrollStateHolder(),
            )
        }
        composeRule.waitForIdle()

        sectionTags.forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        railTags.forEach { composeRule.onNodeWithTag(it).assertIsDisplayed() }
        composeRule.onNodeWithText("正在加载").assertIsDisplayed()
        composeRule.onNodeWithText("暂无内容").assertIsDisplayed()
        composeRule.onNodeWithText("服务暂不可用").assertIsDisplayed()
        sectionTags.forEach { tag ->
            assertTrue(
                "A loading/empty/error section must retain measurable height",
                composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height > 48f,
            )
        }
    }

    private fun readyState(): HomeUiState {
        val cards = (0 until 12).map { index ->
            val song = song(index)
            HomeArtworkCard(
                key = "album:album-$index",
                kind = HomeCardKind.ALBUM,
                song = song,
                songId = song.songId,
                albumId = song.albumId,
                artworkSongId = null,
                artwork = null,
                isPlayable = true,
                queueItemId = if (index % 2 == 0) "queue-item-$index" else null,
            )
        }
        return HomeUiState(
            recentlyUpdated = HomeSectionState.Ready(HomeRecentUpdates(cards)),
            playbackHistory = HomeSectionState.Ready(
                HomePlaybackHistory(cards, cards.mapNotNull { it.songId }),
            ),
            autoplay = HomeSectionState.Ready(HomeAutoplay(cards)),
        )
    }

    private fun song(index: Int) = SongDto(
        songId = "song-$index",
        title = "歌曲 $index",
        fileUri = "file:///music/song-$index.flac",
        artists = listOf("艺人 $index"),
        albumId = "album-$index",
        album = "专辑 $index",
        albumArtists = listOf("艺人 $index"),
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
}
