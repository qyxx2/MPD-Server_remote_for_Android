package io.github.qyxx2.mpdserver.remote.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MainActivity
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemotePlaybackServiceLifecycleDeviceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun serviceWaitsForRestoredOriginAndAttachesToTheExistingSessionOwnerIdempotently() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val application = context as MpdServerRemoteApplication
            application.awaitServerOriginReady()
            assertTrue(application.hasConfiguredServerOrigin)

            val configuredAddress = application.uiPreferences.serverOrigin.first()
            assertEquals(
                ServerOrigin.parseAddress(configuredAddress).address,
                application.runtime.realtimeController.currentOrigin().address,
            )

            assumeTrue(
                "A fresh authoritative current playback snapshot is required for this lifecycle test",
                waitUntil {
                    application.mediaSessionController.projection.value?.confirmedIdentity != null &&
                        application.mediaSessionController.sessionToken != null
                },
            )

            val runtimeBefore = application.runtime
            val realtimeControllerBefore = application.runtime.realtimeController
            val sessionTokenBefore = requireNotNull(application.mediaSessionController.sessionToken)
            val intent = Intent(context, RemotePlaybackService::class.java)

            assertNotNull(context.startService(intent))
            assertTrue(waitUntil { application.mediaSessionController.isServiceCallbackAttached })

            assertNotNull(context.startService(intent))
            assertTrue(waitUntil { application.mediaSessionController.isServiceCallbackAttached })

            assertSame(runtimeBefore, application.runtime)
            assertSame(realtimeControllerBefore, application.runtime.realtimeController)
            assertEquals(sessionTokenBefore, application.mediaSessionController.sessionToken)

            val availability = application.runtime.playerCommands.availability()
            val expectedActions = buildList {
                if (availability.pause) add(PlaybackState.ACTION_PAUSE)
                if (availability.resume) add(PlaybackState.ACTION_PLAY)
                if (availability.previous) add(PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                if (availability.next) add(PlaybackState.ACTION_SKIP_TO_NEXT)
            }.fold(0L) { total, action -> total or action }
            val systemController = MediaController(context, sessionTokenBefore)
            assertEquals(expectedActions, systemController.playbackState?.actions ?: 0L)

            scenario.recreate()
            assertTrue(application.mediaSessionController.isServiceCallbackAttached)
            assertSame(runtimeBefore, application.runtime)
            assertSame(realtimeControllerBefore, application.runtime.realtimeController)
            assertEquals(sessionTokenBefore, application.mediaSessionController.sessionToken)

            // Removing the launcher task must not stop a Service whose manifest says stopWithTask=false.
            scenario.onActivity { it.finishAndRemoveTask() }
            assertTrue(waitUntil { application.mediaSessionController.isServiceCallbackAttached })
            assertSame(runtimeBefore, application.runtime)
            assertEquals(sessionTokenBefore, application.mediaSessionController.sessionToken)

            // onDestroy detaches only the Service callback; the Application-owned Session stays alive.
            context.stopService(intent)
            assertTrue(waitUntil { !application.mediaSessionController.isServiceCallbackAttached })
            assertTrue(application.mediaSessionController.isSessionActive)
            assertEquals(sessionTokenBefore, application.mediaSessionController.sessionToken)
            assertEquals(
                0L,
                MediaController(context, sessionTokenBefore).playbackState?.actions ?: 0L,
            )

            assertNotNull(context.startService(intent))
            assertTrue(waitUntil { application.mediaSessionController.isServiceCallbackAttached })
            assertSame(runtimeBefore, application.runtime)
            assertSame(realtimeControllerBefore, application.runtime.realtimeController)
            assertEquals(sessionTokenBefore, application.mediaSessionController.sessionToken)
            assertEquals(
                expectedActions,
                MediaController(context, sessionTokenBefore).playbackState?.actions ?: 0L,
            )
        }
    }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(40L)
        }
        return predicate()
    }
}
