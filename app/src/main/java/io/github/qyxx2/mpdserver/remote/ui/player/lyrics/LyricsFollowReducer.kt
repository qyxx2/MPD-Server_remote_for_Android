package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

data class LyricsFollowState(
    val following: Boolean = true,
    val gestureActive: Boolean = false,
    val textSelected: Boolean = false,
    val lastInteractionAtMillis: Long? = null,
)

data class LyricsFollowTransition(
    val state: LyricsFollowState,
    val returnToActiveCue: Boolean = false,
)

/** Pure, deterministic reducer for Web-compatible lyric-follow timing and interaction ownership. */
object LyricsFollowReducer {
    const val RETURN_DELAY_MILLIS: Long = 3_000L

    fun initialState(): LyricsFollowState = LyricsFollowState()

    fun onGestureStart(state: LyricsFollowState, atMillis: Long): LyricsFollowState =
        state.copy(following = false, gestureActive = true, lastInteractionAtMillis = atMillis)

    fun onGestureEnd(state: LyricsFollowState, atMillis: Long): LyricsFollowState =
        state.copy(following = false, gestureActive = false, lastInteractionAtMillis = atMillis)

    fun onScroll(state: LyricsFollowState, atMillis: Long): LyricsFollowState =
        state.copy(following = false, lastInteractionAtMillis = atMillis)

    fun onTextSelectionChanged(
        state: LyricsFollowState,
        selected: Boolean,
        atMillis: Long,
    ): LyricsFollowState = state.copy(
        textSelected = selected,
        lastInteractionAtMillis = if (selected) state.lastInteractionAtMillis else
            if (!state.following) atMillis else state.lastInteractionAtMillis,
    )

    fun onCueActivated(state: LyricsFollowState): LyricsFollowState =
        state.copy(
            following = true,
            gestureActive = false,
            textSelected = false,
            lastInteractionAtMillis = null,
        )

    fun onResourceChanged(): LyricsFollowState = initialState()

    fun onTimeout(state: LyricsFollowState, atMillis: Long): LyricsFollowTransition {
        val lastInteraction = state.lastInteractionAtMillis
        val idleIntervalElapsed = lastInteraction != null &&
            atMillis >= lastInteraction &&
            atMillis - lastInteraction >= RETURN_DELAY_MILLIS
        return if (!state.following && !state.gestureActive && !state.textSelected && idleIntervalElapsed) {
            LyricsFollowTransition(
                state = state.copy(following = true, lastInteractionAtMillis = null),
                returnToActiveCue = true,
            )
        } else {
            LyricsFollowTransition(state = state)
        }
    }
}
