package io.github.qyxx2.mpdserver.remote.ui.components

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Integer-pixel rectangle reported by Android for a display cutout. */
data class StatusRingRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Safe system-bar/display-cutout Insets in window pixels. */
data class StatusRingInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class StatusRingWindowGeometry(
    val cutoutBounds: List<StatusRingRect> = emptyList(),
    val safeInsets: StatusRingInsets = StatusRingInsets(),
)

enum class StatusRingAnchorSource {
    DISPLAY_CUTOUT,
    MANUAL_FALLBACK,
}

/** Defaults are also the hand-off contract consumed by the later settings batch. */
data class StatusRingPlacementSettings(
    val sizeScale: Float = 1f,
    val horizontalOffsetDp: Float = 0f,
    val verticalOffsetDp: Float = 0f,
)

data class StatusRingPlacement(
    val anchorSource: StatusRingAnchorSource,
    val centerXpx: Float,
    val centerYpx: Float,
    val diameterPx: Float,
)

/**
 * Pure geometry only: it deliberately receives Android's cutout rectangles rather than guessing
 * camera coordinates. A centered, top-edge cutout is the only automatic anchor accepted here.
 */
object StatusRingPlacementCalculator {
    private const val MIN_DIAMETER_DP = 36f
    private const val CUTOUT_GAP_DP = 4f
    private const val MAX_OFFSET_DP = 24f

    fun calculate(
        screenWidthPx: Int,
        screenHeightPx: Int,
        safeInsets: StatusRingInsets,
        cutoutBounds: List<StatusRingRect>,
        density: Float,
        settings: StatusRingPlacementSettings = StatusRingPlacementSettings(),
    ): StatusRingPlacement {
        val width = screenWidthPx.coerceAtLeast(1).toFloat()
        val height = screenHeightPx.coerceAtLeast(1).toFloat()
        val validDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val topInset = safeInsets.top.coerceIn(0, height.toInt()).toFloat()
        val leftInset = safeInsets.left.coerceAtLeast(0).toFloat().coerceAtMost(width)
        val rightInset = safeInsets.right.coerceAtLeast(0).toFloat().coerceAtMost(width)
        val availableWidth = (width - leftInset - rightInset).coerceAtLeast(1f)

        val centerTolerance = max(16f * validDensity, width * 0.12f)
        val reliableCutout = cutoutBounds
            .asSequence()
            .filter { rect ->
                rect.width > 0 &&
                    rect.height > 0 &&
                    rect.left >= 0 &&
                    rect.top >= 0 &&
                    rect.right <= width &&
                    rect.bottom <= height &&
                    rect.bottom <= topInset + 8f * validDensity &&
                    abs(rect.centerX - width / 2f) <= centerTolerance
            }
            .minByOrNull { rect -> abs(rect.centerX - width / 2f) + rect.top * 0.01f }

        val source = if (reliableCutout == null) {
            StatusRingAnchorSource.MANUAL_FALLBACK
        } else {
            StatusRingAnchorSource.DISPLAY_CUTOUT
        }

        val requestedScale = settings.sizeScale.takeIf { it.isFinite() }?.coerceIn(0.70f, 1.40f) ?: 1f
        val requestedHorizontalOffset = settings.horizontalOffsetDp
            .takeIf { it.isFinite() }?.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP) ?: 0f
        val requestedVerticalOffset = settings.verticalOffsetDp
            .takeIf { it.isFinite() }?.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP) ?: 0f

        val baseDiameter = if (reliableCutout != null) {
            max(
                MIN_DIAMETER_DP * validDensity,
                hypot(reliableCutout.width.toFloat(), reliableCutout.height.toFloat()) +
                    2f * CUTOUT_GAP_DP * validDensity,
            )
        } else {
            // This is explicitly a calibration fallback, not a claim that a camera was detected.
            MIN_DIAMETER_DP * validDensity
        }
        val diameter = min(baseDiameter * requestedScale, availableWidth).coerceAtLeast(1f)
        val radius = diameter / 2f

        val baseX = reliableCutout?.centerX ?: width / 2f
        val baseY = reliableCutout?.centerY ?: topInset / 2f
        val minCenterX = leftInset + radius
        val maxCenterX = (width - rightInset - radius).coerceAtLeast(minCenterX)
        val centerX = (baseX + requestedHorizontalOffset * validDensity).coerceIn(minCenterX, maxCenterX)

        // Keep calibration in the system-bar/cutout band. The top may clip the upper arc when the
        // actual camera reaches the physical screen edge; moving it down would lose the real anchor.
        val centerY = (baseY + requestedVerticalOffset * validDensity).coerceIn(0f, topInset)

        return StatusRingPlacement(
            anchorSource = source,
            centerXpx = centerX.coerceIn(0f, width),
            centerYpx = centerY.coerceIn(0f, height),
            diameterPx = diameter,
        )
    }
}
