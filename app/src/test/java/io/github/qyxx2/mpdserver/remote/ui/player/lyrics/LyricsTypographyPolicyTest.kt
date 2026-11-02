package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LyricsTypographyPolicyTest {
    @Test
    fun activeTimedCueUsesThirtyTwoSp() {
        assertEquals(32.sp, LyricsTypographyPolicy.activeCue.fontSize)
        assertEquals(40.sp, LyricsTypographyPolicy.activeCue.lineHeight)
    }

    @Test
    fun inactiveTimedCueUsesTwentySixSp() {
        assertEquals(26.sp, LyricsTypographyPolicy.inactiveCue.fontSize)
        assertEquals(34.sp, LyricsTypographyPolicy.inactiveCue.lineHeight)
    }

    @Test
    fun overlongSecondLineUsesFixedSingleLineEllipsis() {
        assertEquals(1, LyricsTypographyPolicy.secondLineMaxLines)
        assertFalse(LyricsTypographyPolicy.secondLineSoftWrap)
        assertEquals(TextOverflow.Ellipsis, LyricsTypographyPolicy.secondLineOverflow)
    }
}
