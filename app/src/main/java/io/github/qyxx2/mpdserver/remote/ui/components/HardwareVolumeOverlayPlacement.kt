package io.github.qyxx2.mpdserver.remote.ui.components

/** Window-pixel placement for the transient bar, derived from the exact status-ring geometry. */
data class HardwareVolumeOverlayPlacement(
    val centerXpx: Float,
    val topPx: Float,
    val anchorSource: StatusRingAnchorSource,
)

object HardwareVolumeOverlayPlacementCalculator {
    private const val RING_GAP_DP = 8f

    fun calculate(
        windowWidthPx: Int,
        windowHeightPx: Int,
        geometry: StatusRingWindowGeometry,
        density: Float,
        preferences: StatusRingPlacementSettings = StatusRingPlacementSettings(),
        barHeightPx: Int,
    ): HardwareVolumeOverlayPlacement {
        val width = windowWidthPx.coerceAtLeast(1)
        val height = windowHeightPx.coerceAtLeast(1)
        val validDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val ring = StatusRingPlacementCalculator.calculate(
            screenWidthPx = width,
            screenHeightPx = height,
            safeInsets = geometry.safeInsets,
            cutoutBounds = geometry.cutoutBounds,
            density = validDensity,
            settings = preferences,
        )
        val safe = geometry.safeInsets
        val safeCenterX = safe.left + (width - safe.left - safe.right).coerceAtLeast(1) / 2f
        val ringBottom = ring.centerYpx + ring.diameterPx / 2f
        val requestedTop = maxOf(
            ringBottom + RING_GAP_DP * validDensity,
            safe.top + RING_GAP_DP * validDensity,
        )
        val maximumTop = (height - safe.bottom - barHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
        return HardwareVolumeOverlayPlacement(
            centerXpx = safeCenterX.coerceIn(0f, width.toFloat()),
            topPx = requestedTop.coerceAtMost(maximumTop.toFloat()),
            anchorSource = ring.anchorSource,
        )
    }
}
