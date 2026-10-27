package io.github.qyxx2.mpdserver.remote

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingInsets
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingRect
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.navigation.AppNavHost
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import io.github.qyxx2.mpdserver.remote.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    private var statusRingWindowGeometry by mutableStateOf(StatusRingWindowGeometry())

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

        val application = application as MpdServerRemoteApplication
        val runtime = application.runtime
        setContent {
            val themePreference by application.uiPreferences.themePreference.collectAsStateWithLifecycle(
                initialValue = ThemePreference.FOLLOW_SYSTEM,
            )
            val darkTheme = resolveDarkTheme(
                preference = themePreference,
                systemIsDark = isSystemInDarkTheme(),
            )
            SideEffect {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            MpdServerRemoteTheme(themePreference = themePreference) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost(
                        runtime = runtime,
                        windowGeometry = statusRingWindowGeometry,
                        uiPreferences = application.uiPreferences,
                        preferenceWriteScope = application.uiPreferencesWriteScope,
                        applyServerOrigin = application::saveAndApplyServerOrigin,
                    )
                }
            }
        }
    }
}
