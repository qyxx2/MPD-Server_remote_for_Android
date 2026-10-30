package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueMenuAccentColorTest {
    @Test
    fun queueRowMenuUsesTheRequestedBackgroundColor() {
        assertEquals(Color(0xFFF0DECA), queueRowMenuSurfaceColor())
    }

}
