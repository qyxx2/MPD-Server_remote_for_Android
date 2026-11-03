package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.pow

/** Interpolates an artwork rectangle in a shared coordinate space. */
internal object ArtworkTransitionGeometry {
    fun removeParentTranslation(bounds: Rect, translation: Offset): Rect {
        require(bounds.isFiniteNonEmpty()) { "Artwork bounds must be finite and non-empty." }
        require(translation.x.isFinite() && translation.y.isFinite()) {
            "Parent translation must be finite."
        }
        return bounds.translate(-translation)
    }

    fun interpolate(source: Rect, target: Rect, progress: Float): Rect {
        require(source.isFiniteNonEmpty()) { "Source artwork bounds must be finite and non-empty." }
        require(target.isFiniteNonEmpty()) { "Target artwork bounds must be finite and non-empty." }
        require(progress.isFinite()) { "Artwork transition progress must be finite." }

        val fraction = progress.coerceIn(0f, 1f)
        return Rect(
            left = source.left + (target.left - source.left) * fraction,
            top = source.top + (target.top - source.top) * fraction,
            right = source.right + (target.right - source.right) * fraction,
            bottom = source.bottom + (target.bottom - source.bottom) * fraction,
        )
    }

    /**
     * Downward dismissal has its own artwork path rather than replaying the entry animation
     * backwards. The cover begins shrinking early, travels downward quickly, and delays most of
     * its horizontal movement so it remains visually centered before converging on the mini-player.
     * Each curve is normalized and reaches the measured mini-player rectangle exactly at 1f.
     */
    fun interpolateDismissal(source: Rect, target: Rect, dragProgress: Float): Rect {
        require(source.isFiniteNonEmpty()) { "Source artwork bounds must be finite and non-empty." }
        require(target.isFiniteNonEmpty()) { "Target artwork bounds must be finite and non-empty." }
        require(dragProgress.isFinite()) { "Artwork dismissal progress must be finite." }

        val fraction = dragProgress.coerceIn(0f, 1f)
        val horizontalProgress = fraction.toDouble().pow(1.25).toFloat()
        val verticalProgress = fraction * (2f - fraction)
        val scaleProgress = fraction * (1.7f - 0.7f * fraction)
        val width = source.width + (target.width - source.width) * scaleProgress
        val height = source.height + (target.height - source.height) * scaleProgress
        val centerX = source.center.x + (target.center.x - source.center.x) * horizontalProgress
        val centerY = source.center.y + (target.center.y - source.center.y) * verticalProgress

        return Rect(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
        )
    }

    private fun Rect.isFiniteNonEmpty(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top
}
