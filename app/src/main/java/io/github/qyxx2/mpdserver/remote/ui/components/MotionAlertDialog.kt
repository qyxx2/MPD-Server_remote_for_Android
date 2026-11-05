package io.github.qyxx2.mpdserver.remote.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Alert-dialog content hosted by a full-window Compose Dialog so its scrim and surface share one
 * measured trigger-origin transition. The real Dialog window retains platform Back and IME support.
 */
@Composable
internal fun MotionAlertDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    anchorState: PopupMotionAnchorState,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    dialogTitle: String,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    var dismissRequested by remember { mutableStateOf(false) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    LaunchedEffect(visible) {
        if (visible) dismissRequested = false
    }
    val requestDismiss = {
        if (visible && !dismissRequested) {
            dismissRequested = true
            currentOnDismissRequest()
        }
    }

    // Standalone dialog callers also need real owner geometry; never invent a screen-center origin.
    Box(Modifier.fillMaxSize().popupMotionOwnerFallback(anchorState)) {
        PopupMotionHost(visible = visible, anchorState = anchorState) { surfaceMotionModifier, motion ->
            Dialog(
                onDismissRequest = requestDismiss,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                val dialogView = LocalView.current
                val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
                SideEffect {
                    dialogWindow?.setDimAmount(0f)
                    dialogWindow?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
                }
                Box(
                    modifier = Modifier.fillMaxSize()
                        .imePadding()
                        .forwardForegroundVolumeKeys()
                        .semantics { paneTitle = dialogTitle },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = PopupMotionSpec.scrimAlpha * motion.alpha))
                            .clickable(enabled = visible, onClick = requestDismiss)
                            .testTag("app-dialog-scrim"),
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp)
                            .widthIn(min = 280.dp, max = 560.dp)
                            .then(surfaceMotionModifier)
                            .then(modifier),
                        shape = RoundedCornerShape(28.dp),
                        color = containerColor,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                            ) { title() }
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                            ) { text() }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                dismissButton?.let {
                                    it()
                                    Spacer(Modifier.width(8.dp))
                                }
                                confirmButton()
                            }
                        }
                    }
                    LocalHardwareVolumeOverlayEnvironment.current?.let { overlay ->
                        HardwareVolumeOverlayPopup(
                            state = overlay.state,
                            geometry = overlay.geometry,
                            preferences = overlay.preferences,
                            darkTheme = overlay.darkTheme,
                        )
                    }
                }
            }
        }
    }
}
