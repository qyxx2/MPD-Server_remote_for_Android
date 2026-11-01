package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.qyxx2.mpdserver.remote.protocol.dto.FavoriteMembersDto
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.ResourceEntryState
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class PlaylistFavoritesUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unavailableFavoriteRemainsVisibleOnLibraryRow() {
        val item = LibrarySongRowModel(
            key = "song:orphan-song",
            songId = "orphan-song",
            song = null,
        )
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                CompositionLocalProvider(
                    LocalPlaybackActionsEnabled provides true,
                    LocalFavoriteSongIds provides setOf("orphan-song"),
                    LocalFavoritesResourceFresh provides true,
                ) {
                    LibrarySongRow(item = item, songActions = null)
                }
            }
        }

        composeRule.onNodeWithTag(
            "library-favorite-" + item.key.hashCode(),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText("歌曲已不在音乐库").assertIsDisplayed()
    }

    @Test
    fun unavailableFavoriteCanBeRemovedWithoutPlaybackMetadata() {
        val item = LibrarySongRowModel(
            key = "song:orphan-song-menu",
            songId = "orphan-song",
            song = null,
        )
        val harness = actionHarness(favoriteIds = listOf("orphan-song"))
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                CompositionLocalProvider(
                    LocalPlaybackActionsEnabled provides true,
                    LocalFavoritesActions provides harness.favorites,
                    LocalFavoriteSongIds provides setOf("orphan-song"),
                    LocalFavoritesResourceFresh provides true,
                ) {
                    SongActionMenu(
                        item = item,
                        actions = harness.songs,
                        content = { androidx.compose.material3.Text("Orphan favorite") },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("song-action-area-" + item.key.hashCode()).performClick()
        composeRule.onNodeWithText("立即播放").assertIsNotEnabled()
        composeRule.onNodeWithText("下一首播放").assertIsNotEnabled()
        composeRule.onNodeWithText("添加到播放队列").assertIsNotEnabled()
        composeRule.onNodeWithText("取消收藏").assertIsEnabled()
    }

    @Test
    fun nonFavoriteSongShowsTheAddFavoriteMenuAction() {
        val item = LibrarySongRowModel(
            key = "song:available-song",
            songId = "available-song",
            song = null,
        )
        val harness = actionHarness(favoriteIds = emptyList())
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                CompositionLocalProvider(
                    LocalPlaybackActionsEnabled provides true,
                    LocalFavoritesActions provides harness.favorites,
                    LocalFavoriteSongIds provides emptySet(),
                    LocalFavoritesResourceFresh provides true,
                ) {
                    SongActionMenu(
                        item = item,
                        actions = harness.songs,
                        content = { androidx.compose.material3.Text("Available song") },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("song-action-area-" + item.key.hashCode()).performClick()
        composeRule.onNodeWithText("收藏").assertIsEnabled()
        composeRule.onNodeWithText("立即播放").assertIsNotEnabled()
    }

    private data class ActionHarness(
        val songs: SongActions,
        val favorites: FavoritesActions,
    )

    private fun actionHarness(favoriteIds: List<String>): ActionHarness {
        val activities = MutableStateFlow(emptyList<OperationActivity>())
        val gateway = PlaybackMutationGateway(
            stateProvider = { SnapshotStoreState() },
            activities = activities,
            submitter = { _: MutationIntent -> MutationOutcome.Submitted },
            retryUnknown = { _: String -> MutationOutcome.NotRetryable },
            canRetryUnknown = { _: String -> false },
        )
        val favorites = FavoritesActions(
            gateway = gateway,
            memberState = MutableStateFlow<ResourceEntryState<FavoriteMembersDto>>(
                ResourceEntryState.Ready(FavoriteMembersDto(favoriteIds)),
            ),
            loadMembers = {},
            refreshResources = { true },
        )
        return ActionHarness(
            songs = SongActions(gateway),
            favorites = favorites,
        )
    }
}
