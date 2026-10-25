package io.github.qyxx2.mpdserver.remote

import android.app.Application
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime

/** Process-wide owner for networking and canonical state; UI components only observe this instance. */
class MpdServerRemoteApplication : Application() {
    lateinit var runtime: AppRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime.create(ServerOrigin.parse(DEFAULT_ORIGIN))
    }

    companion object {
        /** A local placeholder until the user configures a server origin; startup does not connect automatically. */
        private const val DEFAULT_ORIGIN = "http://localhost:18000"
    }
}
