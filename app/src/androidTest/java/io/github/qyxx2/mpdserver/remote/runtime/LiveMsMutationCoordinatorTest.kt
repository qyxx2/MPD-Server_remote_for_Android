package io.github.qyxx2.mpdserver.remote.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.data.PlaylistRepository
import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opt-in physical-device verification of a real, isolated, reversible MS mutation.
 *
 * Run with:
 *   -e class io.github.qyxx2.mpdserver.remote.runtime.LiveMsMutationCoordinatorTest
 *   -e liveOrigin http://192.168.3.94:18000
 *
 * Creates a uniquely named user playlist through the app's shared MutationCoordinator,
 * reads it back through PlaylistRepository, deletes only that test-created playlist,
 * and verifies its absence. It never changes playback, Queue, library files, or favorites.
 */
@RunWith(AndroidJUnit4::class)
class LiveMsMutationCoordinatorTest {
    @Test
    fun createsReadsAndDeletesOnlyItsOwnTemporaryPlaylist() {
        val originText = InstrumentationRegistry.getArguments().getString("liveOrigin")
        assumeTrue("Supply -e liveOrigin <http(s)://MS-host:port> to run this opt-in live write test", !originText.isNullOrBlank())

        val origin = ServerOrigin.parse(requireNotNull(originText))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MpdServerRemoteApplication
        val runtime = application.runtime
        val originalOrigin = runtime.realtimeController.currentOrigin()
        val runId = UUID.randomUUID().toString()
        val playlistName = "Task2 Live Write Verification $runId"
        var safeToCleanupByName = false

        try {
            runBlocking {
                runtime.changeOrigin(origin)
                runtime.start()
                withTimeout(30_000L) {
                    runtime.snapshots.state.first { state ->
                        state.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
                            state.freshness == SnapshotFreshness.FRESH &&
                            state.snapshot != null &&
                            state.controlsEligible
                    }
                }
            }

            val before = readPlaylists(origin)
            assertFalse("The random test playlist name must not exist before this run", before.any { it.name == playlistName })
            safeToCleanupByName = true

            val create = submitAndAwait(
                runtime = runtime,
                intent = MutationIntent(
                    id = "task2-live-create-$runId",
                    method = "POST",
                    path = "/api/playlists",
                    payload = """{"name":"$playlistName"}""",
                ),
            )
            assertEquals("Playlist create must have an explicit success receipt: ${create.message}", MutationStatus.SUCCESS, create.status)

            val readBack = readPlaylists(origin)
            val created = readBack.singleOrNull { it.name == playlistName }
                ?: throw AssertionError("Created playlist was not present in authoritative GET /api/playlists")
            assertFalse("The test-created playlist must be a user playlist, never a system playlist", created.isSystem)
            println("TASK2_LIVE_WRITE create=SUCCESS readback=FOUND id=${created.playlistId}")

            val delete = submitAndAwait(
                runtime = runtime,
                intent = MutationIntent(
                    id = "task2-live-delete-$runId",
                    method = "DELETE",
                    path = "/api/playlists/${created.playlistId}",
                    payload = null,
                ),
            )
            assertEquals("Playlist delete (expected 204) must have an explicit success receipt: ${delete.message}", MutationStatus.SUCCESS, delete.status)

            val afterDelete = readPlaylists(origin)
            assertFalse(
                "Deleted test playlist must be absent from the authoritative list",
                afterDelete.any { it.playlistId == created.playlistId || it.name == playlistName },
            )
            println("TASK2_LIVE_WRITE delete=SUCCESS readback=ABSENT")
        } finally {
            try {
                if (safeToCleanupByName) {
                    // If an assertion/transport failure occurred after server-side creation, remove
                    // only the unique name this run proved absent before creating.
                    val remaining = readPlaylists(origin).filter { it.name == playlistName && !it.isSystem }
                    remaining.forEach { item ->
                        val cleanup = submitAndAwait(
                            runtime = runtime,
                            intent = MutationIntent(
                                id = "task2-live-cleanup-$runId-${item.playlistId}",
                                method = "DELETE",
                                path = "/api/playlists/${item.playlistId}",
                                payload = null,
                            ),
                        )
                        assertEquals("Cleanup of this test-created playlist must succeed", MutationStatus.SUCCESS, cleanup.status)
                        val finalList = readPlaylists(origin)
                        assertFalse("Cleanup must leave no test playlist behind", finalList.any { it.playlistId == item.playlistId || it.name == playlistName })
                    }
                }
            } finally {
                runBlocking {
                    runtime.realtimeController.stop()
                    runtime.changeOrigin(originalOrigin)
                }
            }
        }
    }

    private fun submitAndAwait(runtime: AppRuntime, intent: MutationIntent): OperationActivity {
        assertEquals(MutationOutcome.Submitted, runtime.mutations.submit(intent))
        return runBlocking {
            withTimeout(30_000L) {
                runtime.mutations.activities.first { activities ->
                    activities.any { activity ->
                        activity.intentId == intent.id &&
                            activity.status in setOf(MutationStatus.SUCCESS, MutationStatus.FAILED, MutationStatus.UNKNOWN)
                    }
                }.single { it.intentId == intent.id }
            }
        }
    }

    private fun readPlaylists(origin: ServerOrigin): List<PlaylistDto> {
        return when (val result = PlaylistRepository(HttpTransport(origin, OkHttpClient())).getPlaylists()) {
            is ApiResult.Success -> result.value.items
            else -> throw AssertionError("GET /api/playlists failed to decode: $result")
        }
    }
}
