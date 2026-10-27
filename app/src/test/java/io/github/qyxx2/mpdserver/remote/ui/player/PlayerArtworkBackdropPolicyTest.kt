package io.github.qyxx2.mpdserver.remote.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerArtworkBackdropPolicyTest {
    @Test
    fun blurredBackdropDownsamplesLargeArtWithoutChangingAspectRatio() {
        assertEquals(360 to 180, PlayerArtworkBackdropPolicy.downsampledSize(2400, 1200))
        assertEquals(180 to 360, PlayerArtworkBackdropPolicy.downsampledSize(1200, 2400))
        assertEquals(240 to 180, PlayerArtworkBackdropPolicy.downsampledSize(240, 180))
    }

    @Test
    fun backdropMaskAdaptsToTheCurrentLightOrDarkThemeAndHandlesInvalidInput() {
        assertEquals(0.72f, PlayerArtworkBackdropPolicy.maskAlpha(0.95f), 0.001f)
        assertEquals(0.76f, PlayerArtworkBackdropPolicy.maskAlpha(0.05f), 0.001f)
        assertEquals(0.72f, PlayerArtworkBackdropPolicy.maskAlpha(Float.NaN), 0.001f)
    }
}
