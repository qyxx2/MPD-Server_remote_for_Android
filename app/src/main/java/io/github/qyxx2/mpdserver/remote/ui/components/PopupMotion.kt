package io.github.qyxx2.mpdserver.remote.ui.components

import android.view.View
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider

internal object PopupMotionSpec {
    const val durationMillis: Int = 180
    const val initialScale: Float = 0.82f
    const val scrimAlpha: Float = 0.32f

    fun resolveAnchorBounds(triggerBoundsOnScreen: Rect?, ownerBoundsOnScreen: Rect): Rect =
        triggerBoundsOnScreen?.takeUnless { it.isEmpty } ?: ownerBoundsOnScreen

    fun transformOrigin(anchorBoundsOnScreen: Rect, popupBoundsOnScreen: Rect): TransformOrigin {
        if (popupBoundsOnScreen.width <= 0f || popupBoundsOnScreen.height <= 0f) {
            return TransformOrigin.Center
        }
        val center = anchorBoundsOnScreen.center
        return TransformOrigin(
            pivotFractionX = ((center.x - popupBoundsOnScreen.left) / popupBoundsOnScreen.width).coerceIn(0f, 1f),
            pivotFractionY = ((center.y - popupBoundsOnScreen.top) / popupBoundsOnScreen.height).coerceIn(0f, 1f),
        )
    }
}

internal data class PopupMotionValues(
    val alpha: Float,
    val scale: Float,
    val transformOrigin: TransformOrigin,
)

internal enum class PopupMotionAnchorKind { TRIGGER, OWNER }

@Stable
internal class PopupMotionAnchorState {
    var triggerBoundsOnScreen: Rect? by mutableStateOf(null)
        private set
    var ownerBoundsOnScreen: Rect? by mutableStateOf(null)
        private set

    internal fun update(kind: PopupMotionAnchorKind, bounds: Rect) {
        when (kind) {
            PopupMotionAnchorKind.TRIGGER -> triggerBoundsOnScreen = bounds
            PopupMotionAnchorKind.OWNER -> ownerBoundsOnScreen = bounds
        }
    }
}

@Composable
internal fun rememberPopupMotionAnchorState(): PopupMotionAnchorState = remember { PopupMotionAnchorState() }

/** Records screen-space bounds and deliberately retains the last measurement after disposal. */
internal fun Modifier.popupMotionAnchor(
    state: PopupMotionAnchorState,
    kind: PopupMotionAnchorKind,
): Modifier = onGloballyPositioned { coordinates ->
    state.update(kind, coordinates.screenRect())
}

/** Records a measured fallback owner without replacing a more specific owner supplied by the caller. */
internal fun Modifier.popupMotionOwnerFallback(state: PopupMotionAnchorState): Modifier =
    onGloballyPositioned { coordinates ->
        if (state.ownerBoundsOnScreen == null) {
            state.update(PopupMotionAnchorKind.OWNER, coordinates.screenRect())
        }
    }

private fun LayoutCoordinates.screenRect(): Rect {
    val position = positionOnScreen()
    return Rect(
        left = position.x,
        top = position.y,
        right = position.x + size.width,
        bottom = position.y + size.height,
    )
}

private fun View.measuredScreenBoundsOrNull(): Rect? {
    if (!isLaidOut || width <= 0 || height <= 0) return null
    val location = IntArray(2)
    getLocationOnScreen(location)
    return Rect(
        location[0].toFloat(),
        location[1].toFloat(),
        (location[0] + width).toFloat(),
        (location[1] + height).toFloat(),
    )
}

internal val PopupMotionAlphaKey = SemanticsPropertyKey<Float>("PopupMotionAlpha")
internal val PopupMotionScaleKey = SemanticsPropertyKey<Float>("PopupMotionScale")

/**
 * Keeps the popup/dialog host in composition for the complete reverse transition. Owners remain
 * responsible for their visible state, content, focus state and business callbacks.
 */
@Composable
internal fun PopupMotionHost(
    visible: Boolean,
    anchorState: PopupMotionAnchorState,
    onDismissComplete: () -> Unit = {},
    content: @Composable (surfaceModifier: Modifier, motion: PopupMotionValues) -> Unit,
) {
    val transitionState = remember { MutableTransitionState(false) }
    SideEffect { transitionState.targetState = visible }
    val transition = rememberTransition(transitionState, label = "popup-motion")
    val alpha by transition.animateFloat(
        transitionSpec = { tween(PopupMotionSpec.durationMillis, easing = FastOutSlowInEasing) },
        label = "popup-alpha",
    ) { state -> if (state) 1f else 0f }
    val scale by transition.animateFloat(
        transitionSpec = { tween(PopupMotionSpec.durationMillis, easing = FastOutSlowInEasing) },
        label = "popup-scale",
    ) { state -> if (state) 1f else PopupMotionSpec.initialScale }

    var hasOpened by remember { mutableStateOf(false) }
    var dismissalDelivered by remember { mutableStateOf(true) }
    val currentOnDismissComplete by rememberUpdatedState(onDismissComplete)
    LaunchedEffect(visible) {
        if (visible) {
            hasOpened = true
            dismissalDelivered = false
        }
    }
    LaunchedEffect(transitionState.currentState, transitionState.targetState, transitionState.isIdle) {
        if (
            hasOpened &&
            transitionState.isIdle &&
            !transitionState.currentState &&
            !transitionState.targetState &&
            !dismissalDelivered
        ) {
            dismissalDelivered = true
            currentOnDismissComplete()
        }
    }

    if (visible || transitionState.currentState || transitionState.targetState) {
        val localView = LocalView.current
        val popupBounds = remember { mutableStateOf<Rect?>(null) }
        val ownerBounds = anchorState.ownerBoundsOnScreen
            ?: localView.measuredScreenBoundsOrNull()
        val measuredAnchor = anchorState.triggerBoundsOnScreen?.takeUnless { it.isEmpty }
            ?: ownerBounds?.takeUnless { it.isEmpty }
        // Do not invent a centered anchor when a route has not measured its trigger yet. The
        // owner measurement is a safe fallback and will trigger recomposition when it arrives.
        if (measuredAnchor != null) {
            val origin = popupBounds.value?.let { measured ->
                PopupMotionSpec.transformOrigin(measuredAnchor, measured)
            } ?: TransformOrigin.Center
            val values = PopupMotionValues(alpha = alpha, scale = scale, transformOrigin = origin)
            val surfaceModifier = Modifier
                .forwardForegroundVolumeKeys()
                .onGloballyPositioned { popupBounds.value = it.screenRect() }
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = origin
                }
                .semantics {
                    this[PopupMotionAlphaKey] = alpha
                    this[PopupMotionScaleKey] = scale
                }
            content(surfaceModifier, values)
        }
    }
}

/** Position policy matching a standard anchored dropdown: start-align when possible, otherwise
 * end-align and clamp; prefer below the anchor and flip above when the available height requires it.
 */
internal class AnchoredDropdownPositionProvider(
    private val windowMarginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: androidx.compose.ui.unit.IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val marginX = windowMarginPx.coerceAtMost((windowSize.width / 2).coerceAtLeast(0))
        val marginY = windowMarginPx.coerceAtMost((windowSize.height / 2).coerceAtLeast(0))
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val horizontalCandidates = if (layoutDirection == LayoutDirection.Ltr) {
            listOf(anchorBounds.left, anchorBounds.right - popupContentSize.width, anchorBounds.left - popupContentSize.width, anchorBounds.right)
        } else {
            listOf(anchorBounds.right - popupContentSize.width, anchorBounds.left, anchorBounds.right, anchorBounds.left - popupContentSize.width)
        }
        val horizontalInset = marginX.coerceAtMost(maxX / 2)
        val x = horizontalCandidates.firstOrNull { candidate ->
            candidate >= marginX && candidate + popupContentSize.width <= windowSize.width - marginX
        } ?: (horizontalCandidates.firstOrNull() ?: 0).coerceIn(horizontalInset, maxX - horizontalInset)

        val below = anchorBounds.bottom
        val above = anchorBounds.top - popupContentSize.height
        val verticalInset = marginY.coerceAtMost(maxY / 2)
        val y = when {
            below + popupContentSize.height <= windowSize.height - marginY -> below
            above >= marginY -> above
            else -> below.coerceIn(verticalInset, maxY - verticalInset)
        }
        return IntOffset(x, y)
    }
}
