package io.github.qyxx2.mpdserver.remote.media

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemotePlaybackServiceManifestDeviceTest {
    @Test
    fun serviceIsPrivateAndDoesNotClaimLocalMediaPlaybackForegroundServiceType() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, RemotePlaybackService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(0, info.foregroundServiceType)
        }
    }
}
