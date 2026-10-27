package io.github.qyxx2.mpdserver.remote.ui.player

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PlayerArtworkMemoryCacheUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun confirmedArtworkCanBeWarmedBeforeOpeningAndReusedWithoutAnotherRead() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val imageBytes = pngBytes(android.graphics.Color.rgb(220, 40, 80))
        var readCount = 0
        val cache = PlayerArtworkMemoryCache(scope) { _, _ ->
            readCount += 1
            HttpResult.Body(statusCode = 200, contentType = "image/png", bytes = imageBytes)
        }
        val presentation = presentation(artworkStatus = ArtworkReferenceStatus.REFERENCED)
        val key = PlayerArtworkCacheKey.from(presentation, connectionGeneration = 7)

        assertNotNull("confirmed current song with artwork must have a preload key", key)
        try {
            cache.preload(application.runtime, key!!)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                cache.state.value is PlayerArtworkCacheState.Ready
            }
            val ready = cache.state.value as PlayerArtworkCacheState.Ready
            assertSame(key, ready.key)
            assertNotNull(ready.foreground)
            assertNotNull("the blurred background must be prepared before navigation", ready.blurred)
            assertEquals(1, readCount)

            // Re-observing the same canonical song reuses both decoded images and the in-flight
            // resource identity; navigation does not issue a second artwork request.
            cache.preload(application.runtime, key)
            assertEquals(1, readCount)

            composeRule.setContent {
                MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                    FullScreenPlayerScreen(
                        runtime = application.runtime,
                        presentation = presentation,
                        artworkCacheKey = key,
                        artworkCache = cache,
                        onExit = {},
                        onOpenQueue = {},
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("player-artwork-blurred-backdrop").assertIsDisplayed()
            composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
            composeRule.onNodeWithTag("player-artwork-backdrop-theme-mask").assertIsDisplayed()
        } finally {
            cache.clear()
            scope.cancel()
        }
    }

    @Test
    fun unconfirmedOrMissingArtworkNeverProducesAPreloadKey() {
        val presentation = presentation(artworkStatus = ArtworkReferenceStatus.REFERENCED)
        val initialIdentity = PlayerArtworkCacheKey.from(
            presentation,
            connectionGeneration = 3,
            epoch = "epoch-one",
            libraryRevision = 14,
        )!!
        val newLibraryIdentity = PlayerArtworkCacheKey.from(
            presentation,
            connectionGeneration = 3,
            epoch = "epoch-one",
            libraryRevision = 15,
        )!!
        val newEpochIdentity = PlayerArtworkCacheKey.from(
            presentation,
            connectionGeneration = 3,
            epoch = "epoch-two",
            libraryRevision = 14,
        )!!
        assertTrue("library revisions invalidate artwork resource identity", initialIdentity != newLibraryIdentity)
        assertTrue("server epoch changes invalidate artwork resource identity", initialIdentity != newEpochIdentity)
        assertNull(PlayerArtworkCacheKey.from(
            presentation(artworkStatus = ArtworkReferenceStatus.REFERENCED)
                .copy(playbackStatus = ConfirmedPlaybackStatus.UNKNOWN),
            connectionGeneration = 1,
        ))
        assertNull(PlayerArtworkCacheKey.from(
            presentation(artworkStatus = ArtworkReferenceStatus.MISSING),
            connectionGeneration = 1,
        ))
    }

    @Test
    fun aFailedStartupPreloadDoesNotRepeatForTheSameIdentity() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var readCount = 0
        val cache = PlayerArtworkMemoryCache(scope) { _, _ ->
            readCount += 1
            HttpResult.HttpError(503, "text/plain", byteArrayOf())
        }
        val key = PlayerArtworkCacheKey.from(
            presentation(artworkStatus = ArtworkReferenceStatus.REFERENCED),
            connectionGeneration = 5,
        )!!
        try {
            cache.preload(application.runtime, key)
            composeRule.waitUntil(5_000) { cache.state.value is PlayerArtworkCacheState.Failed }
            cache.preload(application.runtime, key)
            assertEquals("same-identity failure must not trigger a second blocking read", 1, readCount)
        } finally {
            cache.clear()
            scope.cancel()
        }
    }

    @Test
    fun latePreviousTrackLoadCannotReplaceTheCurrentTrackArtwork() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val oldReadStarted = CompletableDeferred<Unit>()
        val releaseOldRead = CompletableDeferred<Unit>()
        val oldBytes = pngBytes(android.graphics.Color.RED)
        val newBytes = pngBytes(android.graphics.Color.BLUE)
        val cache = PlayerArtworkMemoryCache(scope) { _, songId ->
            if (songId == "old-song") {
                oldReadStarted.complete(Unit)
                // Simulate a request/decoder that returns late even after a newer song is current.
                withContext(NonCancellable) { releaseOldRead.await() }
                HttpResult.Body(200, "image/png", oldBytes)
            } else {
                HttpResult.Body(200, "image/png", newBytes)
            }
        }
        val oldPresentation = presentation(ArtworkReferenceStatus.REFERENCED, songId = "old-song")
        val newPresentation = presentation(ArtworkReferenceStatus.REFERENCED, songId = "new-song")
        val oldKey = PlayerArtworkCacheKey.from(oldPresentation, connectionGeneration = 3)!!
        val newKey = PlayerArtworkCacheKey.from(newPresentation, connectionGeneration = 3)!!

        try {
            cache.preload(application.runtime, oldKey)
            composeRule.waitUntil(5_000) { oldReadStarted.isCompleted }
            cache.preload(application.runtime, newKey)
            composeRule.waitUntil(5_000) {
                cache.state.value is PlayerArtworkCacheState.Ready &&
                    cache.state.value.key == newKey
            }
            releaseOldRead.complete(Unit)
            composeRule.waitForIdle()

            val ready = cache.state.value as PlayerArtworkCacheState.Ready
            assertSame(newKey, ready.key)
            assertEquals(android.graphics.Color.BLUE, ready.foreground.asAndroidBitmap().getPixel(0, 0))
            assertTrue("the previous request was cancelled when song identity changed", oldReadStarted.isCompleted)
        } finally {
            releaseOldRead.complete(Unit)
            cache.clear()
            scope.cancel()
        }
    }

    private fun presentation(
        artworkStatus: ArtworkReferenceStatus,
        songId: String = "cache-test-song",
    ): PlayerPresentation {
        val song = PlayerSongPresentation(
            songId = songId,
            title = "Cache Test Song",
            fileUri = "file:///music/$songId.flac",
            artists = listOf("Cache Test Artist"),
            albumId = "album-cache-test",
            album = "Cache Test Album",
            albumArtists = emptyList(),
            trackNumber = 1,
            discNumber = 1,
            durationSeconds = 180.0,
            lyrics = null,
            lyricsFormat = null,
            lyricsSource = null,
            lyricsStatus = LyricsResourceStatus.MISSING,
            rawLyricsStatus = "missing",
            artworkReferenceStatus = artworkStatus,
        )
        return PlayerPresentation(
            playbackStatus = ConfirmedPlaybackStatus.PAUSED,
            actualState = null,
            businessPlaybackState = null,
            playbackDiagnostics = emptyList(),
            currentOccurrenceId = "queue-item-cache-test",
            songStatus = SongMetadataStatus.AVAILABLE,
            song = song,
            artworkReferenceStatus = artworkStatus,
            sourceFormat = SourceFormatPresentation(SourceFormatStatus.UNKNOWN, null, null, null, null),
            output = OutputPresentation(
                observationStatus = OutputObservationStatus.UNKNOWN,
                dacStatus = DacOutputStatus.UNKNOWN,
                dacFormat = null,
                dacSampleRateHz = null,
                dacBitDepth = null,
                dacChannels = null,
                errorCode = null,
                errorMessage = null,
                diagnostics = emptyList(),
            ),
            controls = PlayerControlCapabilities(),
            resourceDiagnostics = emptyList(),
        )
    }

    private fun pngBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
