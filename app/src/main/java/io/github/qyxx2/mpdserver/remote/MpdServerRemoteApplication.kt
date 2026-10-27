package io.github.qyxx2.mpdserver.remote

import android.app.Application
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import io.github.qyxx2.mpdserver.remote.ui.preferences.uiPreferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Process-wide owners for networking and local UI preferences. */
class MpdServerRemoteApplication : Application() {
    lateinit var runtime: AppRuntime
        private set

    lateinit var uiPreferences: UiPreferencesRepository
        private set

    /** Keeps local preference writes alive when the Settings destination leaves composition. */
    val uiPreferencesWriteScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serverOriginReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        uiPreferences = UiPreferencesRepository(uiPreferencesDataStore)
        runtime = AppRuntime.create(ServerOrigin.parse(DEFAULT_ORIGIN))

        // Restore the configured local origin without probing the server or creating another owner.
        uiPreferencesWriteScope.launch {
            var shouldStartConfiguredRuntime = false
            try {
                val storedAddress = uiPreferences.serverOrigin.first()
                val restoredOrigin = runCatching {
                    ServerOrigin.parseAddress(storedAddress)
                }.getOrElse {
                    ServerOrigin.parseAddress(DEFAULT_ORIGIN)
                }
                runtime.changeOrigin(restoredOrigin)
                shouldStartConfiguredRuntime =
                    storedAddress == restoredOrigin.address && restoredOrigin.address != DEFAULT_ORIGIN
                if (storedAddress != restoredOrigin.address) {
                    uiPreferences.setServerOrigin(restoredOrigin)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preferences may be temporarily unavailable; keep the valid local placeholder.
                shouldStartConfiguredRuntime = false
                runtime.changeOrigin(ServerOrigin.parse(DEFAULT_ORIGIN))
            } finally {
                // Reuse this one Runtime owner when an address was explicitly configured. A fresh
                // install with only the local placeholder does not initiate a network connection.
                if (shouldStartConfiguredRuntime) runtime.start()
                serverOriginReady.complete(Unit)
            }
        }
    }

    /** Persist and apply one validated origin through the existing app-scoped Runtime. */
    suspend fun saveAndApplyServerOrigin(origin: ServerOrigin) {
        serverOriginReady.await()
        uiPreferences.setServerOrigin(origin)
        runtime.changeOrigin(origin)
        runtime.start()
    }

    companion object {
        /** A local placeholder until the user configures a server origin; it does not connect automatically. */
        private const val DEFAULT_ORIGIN = ServerOrigin.DEFAULT_ADDRESS
    }
}
