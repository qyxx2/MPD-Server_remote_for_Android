package io.github.qyxx2.mpdserver.remote.network

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigurationTest {
    @Test
    fun configuredMsOriginPermitsCleartextHttp() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as MpdServerRemoteApplication
        val savedAddress = application.uiPreferences.serverOrigin.first()
        val origin = ServerOrigin.parseAddress(savedAddress)
        val policy = NetworkSecurityPolicy.getInstance()

        assertTrue(
            "HTTP is intentional for the user-configured MS origin; host must be cleartext-permitted: $savedAddress",
            policy.isCleartextTrafficPermitted(origin.httpUrl.host),
        )
    }
}
