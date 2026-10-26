package io.github.qyxx2.mpdserver.remote

import android.app.Application
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import io.github.qyxx2.mpdserver.remote.ui.preferences.uiPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide owners for networking and local UI preferences. */
class MpdServerRemoteApplication : Application() {
    lateinit var runtime: AppRuntime
        private set

    lateinit var uiPreferences: UiPreferencesRepository
        private set

    /** Keeps local preference writes alive when the Settings destination leaves composition. */
    val uiPreferencesWriteScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        uiPreferences = UiPreferencesRepository(uiPreferencesDataStore)
        runtime = AppRuntime.create(ServerOrigin.parse(DEFAULT_ORIGIN))
    }

    companion object {
        /** A local placeholder until the user configures a server origin; startup does not connect automatically. */
        private const val DEFAULT_ORIGIN = "http://localhost:18000"
    }
}
