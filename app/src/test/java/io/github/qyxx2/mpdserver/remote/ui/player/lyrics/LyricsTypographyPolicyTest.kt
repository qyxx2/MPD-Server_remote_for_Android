package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import androidx.compose.ui.text.style.TextAlign
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
    fun overlongSecondLineUsesCenteredSymmetricClip() {
        assertEquals(1, LyricsTypographyPolicy.secondLineMaxLines)
        assertFalse(LyricsTypographyPolicy.secondLineSoftWrap)
        assertEquals(TextAlign.Center, LyricsTypographyPolicy.textAlignment)
        assertEquals(TextOverflow.Clip, LyricsTypographyPolicy.secondLineOverflow)
    }
    @Test
    fun viewportShowsAtMostEightCompleteLinesAndReducesForShortHeights() {
        assertEquals(8, LyricsTypographyPolicy.visibleLineCount(480f, 44f))
        assertEquals(7, LyricsTypographyPolicy.visibleLineCount(480f, 44f, activeCueLineCount = 1))
        assertEquals(8, LyricsTypographyPolicy.visibleLineCount(480f, 44f, activeCueLineCount = 2))
        assertEquals(5, LyricsTypographyPolicy.visibleLineCount(220f, 44f))
        assertEquals(1, LyricsTypographyPolicy.visibleLineCount(10f, 44f))
    }
}
