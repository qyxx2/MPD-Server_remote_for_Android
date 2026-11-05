package io.github.qyxx2.mpdserver.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun exposesFrozenGradleApplicationIdentity() {
        assertEquals("io.github.qyxx2.mpdserver.remote", BuildConfig.APPLICATION_ID)
        assertEquals("MPD-Server Remote", BuildConfig.APP_DISPLAY_NAME)
        assertEquals(10002, BuildConfig.VERSION_CODE)
        assertEquals("1.0.2", BuildConfig.VERSION_NAME)
    }
}
