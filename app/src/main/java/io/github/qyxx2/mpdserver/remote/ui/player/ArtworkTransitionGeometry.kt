package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Interpolates an artwork rectangle in a shared coordinate space. */
internal object ArtworkTransitionGeometry {
    fun removeParentTranslation(bounds: Rect, translation: Offset): Rect {
        require(bounds.isFiniteNonEmpty()) { "Artwork bounds must be finite and non-empty." }
        require(translation.x.isFinite() && translation.y.isFinite()) {
            "Parent translation must be finite."
        }
        return bounds.translate(-translation)
    }

    /**
     * Maps the live Player sheet translation to artwork travel. The cover must move down by
     * exactly the same number of pixels as the sheet until its top reaches the measured
     * mini-player artwork top; normalizing by the full window height makes it visibly lag when
     * the two artwork anchors are closer than the full Player layer height.
     */
    fun dismissalProgress(source: Rect, target: Rect, offsetPx: Float): Float {
        require(source.isFiniteNonEmpty()) { "Source artwork bounds must be finite and non-empty." }
        require(target.isFiniteNonEmpty()) { "Target artwork bounds must be finite and non-empty." }
        require(offsetPx.isFinite()) { "Artwork dismissal offset must be finite." }

        val verticalTravel = target.top - source.top
        if (verticalTravel <= 0f) return 0f
        return (offsetPx / verticalTravel).coerceIn(0f, 1f)
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

    private fun Rect.isFiniteNonEmpty(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top
}
