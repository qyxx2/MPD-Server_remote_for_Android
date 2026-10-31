package io.github.qyxx2.mpdserver.remote.ui.search

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchGroupDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchGroupKindDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.ResourceSessionIdentity
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScreenUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: SearchController
    private val requestedQueries = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var openedGroup: Triple<SearchGroupKindDto, String, String>? = null
    private lateinit var navController: NavHostController

    @After
    fun closeController() {
        if (::controller.isInitialized) controller.close()
        ownerScope.cancel()
    }

    @Test
    fun searchInputShowsServerOrderedGroupsAndNavigatesUsingOpaqueSourceId() {
        val identity = MutableStateFlow(
            SearchResourceIdentity(
                session = ResourceSessionIdentity("http://test.example:18000", 1L, "epoch-test"),
                libraryRevision = 5L,
                isFresh = true,
            ),
        )
        controller = SearchController(
            readSearchResults = { query ->
                requestedQueries += query
                ApiResult.Success(
                    SearchResultsDto(
                        groups = listOf(
                            SearchGroupDto(
                                kind = SearchGroupKindDto.ALBUMS,
                                sourceId = "album & #1",
                                name = "Album result",
                                songIds = listOf("song-2", "song-1"),
                            ),
                        ),
                        songIds = listOf("song-1", "song-3"),
                    ),
                    200,
                )
            },
            readSong = { id -> ApiResult.Success(song(id), 200) },
            identityFlow = identity,
            parentScope = ownerScope,
            debounceMillis = 0L,
        )

        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { navController = controller }
            val entry by controller.currentBackStackEntryAsState()
            val route = entry?.destination?.route
            LaunchedEffect(route) {
                if (route == "search") this@SearchScreenUiTest.controller.activate()
                else this@SearchScreenUiTest.controller.deactivate()
            }
            NavHost(navController = controller, startDestination = "search") {
                composable("search") {
                    SearchScreen(
                        controller = this@SearchScreenUiTest.controller,
                        onOpenCategory = { kind, sourceId, name ->
                            openedGroup = Triple(kind, sourceId, name)
                            controller.navigate(SearchProjection.categoryRoute(kind, sourceId))
                        },
                    )
                }
                composable(
                    route = LibraryRoutes.DETAIL_PATTERN,
                    arguments = listOf(
                        navArgument(LibraryRoutes.KIND_ARGUMENT) { type = NavType.StringType },
                        navArgument(LibraryRoutes.ID_ARGUMENT) { type = NavType.StringType },
                    ),
                ) { destination ->
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.Text(
                            destination.arguments?.getString(LibraryRoutes.ID_ARGUMENT).orEmpty(),
                            modifier = androidx.compose.ui.Modifier.testTag("search-detail-source-id"),
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { controller.popBackStack() },
                            modifier = androidx.compose.ui.Modifier.testTag("library-page-back"),
                        ) { androidx.compose.material3.Text("返回") }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("search-query-field").performTextInput("  jazz  ")
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("search-results").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("search-group-albums-album & #1").assertIsDisplayed()
        composeRule.onNodeWithTag("search-group-albums-album & #1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == LibraryRoutes.DETAIL_PATTERN &&
                navController.currentBackStackEntry?.arguments?.getString(LibraryRoutes.ID_ARGUMENT) == "album & #1"
        }
        composeRule.onNodeWithTag("search-detail-source-id").assertTextEquals("album & #1")
        assertEquals(
            Triple(SearchGroupKindDto.ALBUMS, "album & #1", "Album result"),
            openedGroup,
        )

        // The detail stays above Search. Returning reactivates Search with the same query.
        composeRule.onNodeWithTag("library-page-back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            navController.currentDestination?.route == "search" &&
                composeRule.onAllNodesWithTag("search-results")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeRule.onNodeWithTag("search-query-field").assertIsDisplayed()
        assertEquals(listOf("jazz", "jazz"), requestedQueries.toList())
    }

    private fun song(id: String) = SongDto(
        songId = id,
        title = id,
        fileUri = "music/$id.flac",
        artists = listOf("Artist"),
        albumId = null,
        album = null,
        albumArtists = emptyList(),
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
