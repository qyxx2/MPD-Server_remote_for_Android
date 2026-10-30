package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlayerProgressClockRegionUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun subSecondClockTicksRedrawProgressWithoutRecomposingLabelsOrParent() {
        val clockPosition = MutableStateFlow<Double?>(10.1)
        val parentCompositions = AtomicInteger()
        val regionCompositions = AtomicInteger()
        val drawPasses = AtomicInteger()
        val lastDrawnFraction = AtomicReference<Float?>(null)

        composeRule.setContent {
            SideEffect { parentCompositions.incrementAndGet() }
            Column {
                PlayerProgressClockRegion(
                    clockPosition = clockPosition,
                    durationSeconds = 100.0,
                    fallbackPositionSeconds = null,
                    previewPositionSeconds = null,
                ) { readoutPosition, drawProgress ->
                    SideEffect { regionCompositions.incrementAndGet() }
                    Text(readoutPosition?.toString() ?: "unknown")
                    Canvas(
                        modifier = Modifier
                            .size(24.dp)
                            .drawWithContent {
                                val fraction = drawProgress.value.coerceIn(0f, 1f)
                                lastDrawnFraction.set(fraction)
                                drawPasses.incrementAndGet()
                                drawRect(
                                    color = Color.Red,
                                    size = androidx.compose.ui.geometry.Size(
                                        width = size.width * fraction,
                                        height = size.height,
                                    ),
                                )
                            },
                    ) {
                        // The draw modifier above owns the proportional progress fill.
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val parentBeforeTick = parentCompositions.get()
        val regionBeforeTick = regionCompositions.get()
        val drawsBeforeTick = drawPasses.get()
        composeRule.runOnUiThread { clockPosition.value = 10.2 }

        composeRule.waitUntil(5_000) { drawPasses.get() > drawsBeforeTick }
        assertTrue(
            "the precise progress fraction must invalidate the draw phase",
            drawPasses.get() > drawsBeforeTick,
        )
        assertEquals(
            "the drawn fill width must follow the sub-second clock sample",
            0.102f,
            requireNotNull(lastDrawnFraction.get()),
            0.001f,
        )
        composeRule.onAllNodesWithText("10.1").assertCountEquals(1)
        assertEquals(
            "sub-second ticks must not recompose the progress labels",
            regionBeforeTick,
            regionCompositions.get(),
        )
        assertEquals(
            "sub-second ticks must not recompose the player parent",
            parentBeforeTick,
            parentCompositions.get(),
        )

        composeRule.runOnUiThread { clockPosition.value = 11.0 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("11.0")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
        composeRule.onAllNodesWithText("11.0").assertCountEquals(1)
        assertTrue(
            "the readout region must update when the elapsed whole second changes",
            regionCompositions.get() > regionBeforeTick,
        )
        assertEquals(
            "whole-second readout updates must remain scoped below the screen parent",
            parentBeforeTick,
            parentCompositions.get(),
        )
    }
}
