package io.github.qyxx2.mpdserver.remote

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.qyxx2.mpdserver.remote.runtime.ForegroundHardwareVolumeKeyInputAdapter
import io.github.qyxx2.mpdserver.remote.runtime.ForegroundHardwareVolumeOverlayController
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeOverlayPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.HardwareVolumeOverlayEnvironment
import io.github.qyxx2.mpdserver.remote.ui.components.HardwareVolumeOverlayPopup
import io.github.qyxx2.mpdserver.remote.ui.components.LocalHardwareVolumeKeyInputAdapter
import io.github.qyxx2.mpdserver.remote.ui.components.LocalHardwareVolumeOverlayEnvironment
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingInsets
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingRect
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavHost
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import io.github.qyxx2.mpdserver.remote.ui.theme.resolveDarkTheme
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var statusRingWindowGeometry by mutableStateOf(StatusRingWindowGeometry())
    internal lateinit var volumeKeyInputAdapter: ForegroundHardwareVolumeKeyInputAdapter
    private lateinit var volumeOverlayController: ForegroundHardwareVolumeOverlayController

    override fun onStart() {
        super.onStart()
        (application as? MpdServerRemoteApplication)?.retryMediaServiceStartFromForeground()
    }

    override fun onResume() {
        super.onResume()
        if (::volumeKeyInputAdapter.isInitialized) volumeKeyInputAdapter.setForeground(true)
    }

    override fun onPause() {
        if (::volumeKeyInputAdapter.isInitialized) volumeKeyInputAdapter.setForeground(false)
        if (::volumeOverlayController.isInitialized) volumeOverlayController.hide()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::volumeOverlayController.isInitialized && volumeOverlayController.state.value.visible) {
            // Focusable app-owned Popup windows are separate windows. Re-create our non-focusable
            // Compose Popup after them so it occupies the latest app-popup layer, without
            // requesting SYSTEM_ALERT_WINDOW or changing focus ownership.
            window.decorView.postDelayed({
                if (!isFinishing &&
                    lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
                ) {
                    volumeOverlayController.bringToFront()
                }
            }, 50L)
        }
    }

    /** Intercept once at the Activity boundary; app-owned Popup surfaces forward to this adapter. */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::volumeKeyInputAdapter.isInitialized &&
            volumeKeyInputAdapter.dispatch(event.keyCode, event.action)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::volumeOverlayController.isInitialized) volumeOverlayController.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The app owns the full window canvas. Compose content below applies the reported safe Insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val decorView = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val cutoutBounds = insets.displayCutout?.boundingRects.orEmpty().map { rect ->
                StatusRingRect(
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                )
            }
            statusRingWindowGeometry = StatusRingWindowGeometry(
                cutoutBounds = cutoutBounds,
                safeInsets = StatusRingInsets(
                    left = safe.left,
                    top = safe.top,
                    right = safe.right,
                    bottom = safe.bottom,
                ),
            )
            insets
        }
        decorView.post { ViewCompat.requestApplyInsets(decorView) }

        val app = application as MpdServerRemoteApplication
        val runtime = app.runtime
        volumeOverlayController = ForegroundHardwareVolumeOverlayController(
            scope = lifecycleScope,
            presentationProvider = {
                HardwareVolumeOverlayPresentation.from(
                    runtime.snapshots.state.value,
                    runtime.hardwareVolumeCommands.state.value,
                )
            },
            onVolumeKey = { direction -> runtime.hardwareVolumeCommands.step(direction) },
        )
        volumeKeyInputAdapter = ForegroundHardwareVolumeKeyInputAdapter(volumeOverlayController::onVolumeKey)
        lifecycleScope.launch {
            combine(runtime.snapshots.state, runtime.hardwareVolumeCommands.state) { snapshot, command ->
                HardwareVolumeOverlayPresentation.from(snapshot, command)
            }.collect(volumeOverlayController::updatePresentation)
        }

        setContent {
            val themePreference by app.uiPreferences.themePreference.collectAsStateWithLifecycle(
                initialValue = ThemePreference.FOLLOW_SYSTEM,
            )
            val statusRingPreferences by app.uiPreferences.statusRingPreferences.collectAsStateWithLifecycle(
                initialValue = StatusRingPreferences.DEFAULT,
            )
            val overlayState by volumeOverlayController.state.collectAsStateWithLifecycle()
            val darkTheme = resolveDarkTheme(
                preference = themePreference,
                systemIsDark = isSystemInDarkTheme(),
            )
            SideEffect {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            val overlayEnvironment = HardwareVolumeOverlayEnvironment(
                state = overlayState,
                geometry = statusRingWindowGeometry,
                preferences = statusRingPreferences,
                darkTheme = darkTheme,
            )
            CompositionLocalProvider(
                LocalHardwareVolumeKeyInputAdapter provides volumeKeyInputAdapter,
                LocalHardwareVolumeOverlayEnvironment provides overlayEnvironment,
            ) {
                MpdServerRemoteTheme(themePreference = themePreference) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            AppNavHost(
                                runtime = runtime,
                                windowGeometry = statusRingWindowGeometry,
                                uiPreferences = app.uiPreferences,
                                preferenceWriteScope = app.uiPreferencesWriteScope,
                                applyServerOrigin = app::saveAndApplyServerOrigin,
                            )
                        }
                        // App-window fallback. Focusable Popup windows are re-ordered by the
                        // Activity focus callback; Dialog windows host the same projection locally.
                        HardwareVolumeOverlayPopup(
                            state = overlayState,
                            geometry = statusRingWindowGeometry,
                            preferences = statusRingPreferences,
                            darkTheme = darkTheme,
                        )
                    }
                }
            }
        }
    }
}
