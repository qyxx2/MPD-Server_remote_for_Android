package io.github.qyxx2.mpdserver.remote.ui.player

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapability
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapabilityStatus
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCapabilityPolicy
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCanonicalTuple
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCommandPhase
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeCommandState
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.protocol.dto.ObservationFreshnessDto
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionAlphaKey
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionScaleKey
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionAnchorKind
import io.github.qyxx2.mpdserver.remote.ui.components.PopupMotionAnchorState
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FullScreenPlayerPresentationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersArtworkFirstThenTitleArtistAlbumAndSourceWithoutInventedArtwork() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val presentation = testPresentation()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = presentation,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-artwork-backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-backdrop-placeholder").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-backdrop-theme-mask").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
        composeRule.onNodeWithText("无封面").assertIsDisplayed()
        composeRule.onNodeWithTag("player-song-title").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artist-0").assertIsDisplayed()
        composeRule.onNodeWithTag("player-album-link").assertIsDisplayed()
        composeRule.onNodeWithTag("player-source-format").assertIsDisplayed()

        val titleTop = composeRule.onNodeWithTag("player-song-title").fetchSemanticsNode().boundsInRoot.top
        val artistTop = composeRule.onNodeWithTag("player-artist-0").fetchSemanticsNode().boundsInRoot.top
        val albumTop = composeRule.onNodeWithTag("player-album-link").fetchSemanticsNode().boundsInRoot.top
        val formatTop = composeRule.onNodeWithTag("player-source-format").fetchSemanticsNode().boundsInRoot.top
        assertTrue("title should precede artist", titleTop < artistTop)
        assertTrue("artist should precede album", artistTop < albumTop)
        assertTrue("album should precede source-file format", albumTop < formatTop)

        val density = composeRule.activity.resources.displayMetrics.density
        val controlsBounds = composeRule.onNodeWithTag("player-playback-controls")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "main playback controls need a taller visual zone to stay distinct from bottom navigation",
            controlsBounds.height / density >= 88f,
        )
        listOf("player-previous", "player-play-pause", "player-next").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must keep at least a 72dp touch target", bounds.width / density >= 72f)
            assertTrue("$tag must keep at least a 72dp touch target", bounds.height / density >= 72f)
        }
        val previousGlyph = composeRule.onNodeWithTag("player-glyph-previous", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val playGlyph = composeRule.onNodeWithTag("player-glyph-play", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val nextGlyph = composeRule.onNodeWithTag("player-glyph-next", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("previous glyph should be visibly larger than the old 26dp drawing", previousGlyph.width / density >= 44f)
        assertTrue("central play glyph should be the visual focus", playGlyph.width / density >= 48f)
        assertTrue("central play glyph should be the visual focus", playGlyph.height / density >= 48f)
        assertTrue("next glyph should be visibly larger than the old 26dp drawing", nextGlyph.width / density >= 44f)
    }

    @Test
    fun progressTrackAndReadoutsMatchReferenceWithoutDivider() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val base = testPresentation()
        val presentation = base.copy(
            song = base.song!!.copy(durationSeconds = 227.0),
            sourceFormat = SourceFormatPresentation(
                status = SourceFormatStatus.COMPLETE,
                codec = "FLAC",
                bitDepth = 24,
                sampleRateHz = 96000,
                channelCount = 2,
            ),
        )

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = presentation,
                    mediaHandoffActive = true,
                    mediaHandoffPositionSeconds = 80.0,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-progress-slider").assertIsDisplayed()
        composeRule.onNodeWithTag("player-progress-track").assertIsDisplayed()
        composeRule.onNodeWithTag("player-source-format-pill").assertIsDisplayed()
        composeRule.onNodeWithText("FLAC · 24 bit · 96 kHz · 2 ch").assertIsDisplayed()
        composeRule.onNodeWithTag("player-remaining-time").assertIsDisplayed()
        composeRule.onNodeWithText("-2:27").assertIsDisplayed()
        composeRule.onAllNodesWithTag("player-progress-divider").assertCountEquals(0)

        val density = composeRule.activity.resources.displayMetrics.density
        val track = composeRule.onNodeWithTag("player-progress-track").fetchSemanticsNode().boundsInRoot
        val elapsed = composeRule.onNodeWithTag("player-elapsed-time").fetchSemanticsNode().boundsInRoot
        val format = composeRule.onNodeWithTag("player-source-format-pill").fetchSemanticsNode().boundsInRoot
        val remaining = composeRule.onNodeWithTag("player-remaining-time").fetchSemanticsNode().boundsInRoot
        assertTrue("reference track should be a thick rounded bar, not a 1dp divider", track.height / density >= 7f)
        assertTrue("time and source format belong below the progress track", track.bottom <= elapsed.top)
        assertTrue("source format should share the time readout row", abs(format.center.y - elapsed.center.y) <= density * 8f)
        assertTrue("elapsed and remaining times should share the readout row", abs(remaining.center.y - elapsed.center.y) <= density * 8f)
    }

    @Test
    fun nowPlayingNavigationGlyphUsesThreeSimpleVerticalStrokes() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                PlayerGlyph(
                    kind = PlayerGlyphKind.NOW_PLAYING,
                    tint = Color.Red,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
        composeRule.waitForIdle()

        val pixels = composeRule.onNodeWithTag("player-glyph-now_playing").captureToImage().toPixelMap()
        val occupiedColumns = (0 until pixels.width).map { x ->
            (0 until pixels.height).any { y ->
                val pixel = pixels[x, y]
                pixel.red > pixel.green + 0.15f && pixel.red > pixel.blue + 0.15f
            }
        }
        val strokeGroups = occupiedColumns.fold(0 to false) { (count, wasOccupied), occupied ->
            (count + if (occupied && !wasOccupied) 1 else 0) to occupied
        }.first
        assertEquals("the now-playing navigation glyph should be a minimal 3-stroke waveform", 3, strokeGroups)
    }

    @Test
    fun artistAndAlbumClicksEmitNamesAndTypedServerOwnedIds() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        var artistName: String? = null
        var entityRequest: PlayerEntityNavigationRequest? = null

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = testPresentation(),
                    onExit = {},
                    onOpenQueue = {},
                    onArtistClick = { artistName = it },
                    onEntityNavigationRequest = { entityRequest = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-artist-0").performClick()
        composeRule.onNodeWithTag("player-album-link").performClick()

        assertEquals("Test Artist", artistName)
        assertEquals(
            PlayerEntityNavigationRequest(
                type = PlayerEntityType.ALBUM,
                id = "album-server-42",
                displayName = "Test Album",
            ),
            entityRequest,
        )
    }

    @Test
    fun dacActionOpensCompactTwoRowPanelWithServerGatedSlider() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val base = testPresentation()
        val presentation = base.copy(
            output = OutputPresentation(
                observationStatus = OutputObservationStatus.FRESH,
                dacStatus = DacOutputStatus.ACTIVE,
                dacFormat = "pcm",
                dacSampleRateHz = 44100,
                dacBitDepth = 16,
                dacChannels = 2,
                errorCode = null,
                errorMessage = null,
                diagnostics = emptyList(),
            ),
        )

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = presentation,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.onNodeWithTag("player-bottom-output").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("dac-output-info").assertIsDisplayed()
        composeRule.onNodeWithText("DAC · PCM · 44.1 kHz · 16 bit · 2 ch").assertIsDisplayed()
        composeRule.onNodeWithText("已启用").assertIsDisplayed()
        composeRule.onNodeWithTag("dac-volume-row").assertIsDisplayed()
        val serverAllowsVolumeWrite = HardwareVolumeCapabilityPolicy.evaluate(application.runtime.snapshots.state.value).canWrite
        if (serverAllowsVolumeWrite) {
            composeRule.onNodeWithTag("dac-volume-slider").assertIsEnabled()
        } else {
            composeRule.onNodeWithTag("dac-volume-slider").assertIsNotEnabled()
        }
        composeRule.onNodeWithTag("dac-volume-value").assertIsDisplayed()
        composeRule.onAllNodesWithTag("player-output-details", useUnmergedTree = true).assertCountEquals(0)
        val popupSurface = composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true)
        val popupSemantics = popupSurface.fetchSemanticsNode().config
        assertEquals(1f, popupSemantics[PopupMotionAlphaKey], 0.001f)
        assertEquals(1f, popupSemantics[PopupMotionScaleKey], 0.001f)

        val density = composeRule.activity.resources.displayMetrics.density
        val surfaceNode = composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true)
            .fetchSemanticsNode()
        val surface = surfaceNode.boundsInRoot
        val rootNode = composeRule.onNodeWithTag("full-screen-player-root").fetchSemanticsNode()
        val triggerNode = composeRule.onNodeWithTag("player-bottom-output").fetchSemanticsNode()
        val surfaceScreen = surfaceNode.layoutInfo.coordinates.positionOnScreen()
        val triggerScreen = triggerNode.layoutInfo.coordinates.positionOnScreen()
        val triggerSize = triggerNode.layoutInfo.coordinates.size
        val rootScreen = rootNode.layoutInfo.coordinates.positionOnScreen()
        val surfaceSize = surfaceNode.layoutInfo.coordinates.size
        val rootSize = rootNode.layoutInfo.coordinates.size
        val widthDp = surface.width / density
        val volumeTrackTag = if (serverAllowsVolumeWrite) "dac-volume-track" else "dac-volume-slider"
        val volumeTrack = composeRule.onNodeWithTag(volumeTrackTag).fetchSemanticsNode().boundsInRoot
        if (serverAllowsVolumeWrite) {
            val sliderTouchRow = composeRule.onNodeWithTag("dac-volume-row").fetchSemanticsNode().boundsInRoot
            assertTrue("the interactive Slider row must keep at least a 48dp touch target", sliderTouchRow.height / density >= 48f)
        }
        assertTrue("DAC panel should size to its information instead of always using a wide dialog", widthDp < 360f)
        assertTrue("DAC panel must not exceed 520dp", widthDp <= 520f)
        assertTrue("DAC panel must keep at least 16dp horizontal safe margin", (surfaceScreen.x - rootScreen.x) / density >= 16f)
        assertTrue("DAC panel must keep at least 16dp horizontal safe margin", (rootScreen.x + rootSize.width - surfaceScreen.x - surfaceSize.width) / density >= 16f)
        assertTrue(
            "DAC panel must sit above the DAC trigger; panelScreen=$surfaceScreen size=${surfaceNode.layoutInfo.coordinates.size} triggerScreen=$triggerScreen",
            surfaceScreen.y + surfaceNode.layoutInfo.coordinates.size.height < triggerScreen.y,
        )
        val startEdgeDelta = abs(surfaceScreen.x - triggerScreen.x)
        val endEdgeDelta = abs(surfaceScreen.x + surfaceSize.width - triggerScreen.x - triggerSize.width)
        assertTrue(
            "DAC popup should align to the trigger after accounting for attached-popup shadow insets: surface=$surfaceScreen size=$surfaceSize trigger=$triggerScreen size=$triggerSize startDelta=$startEdgeDelta endDelta=$endEdgeDelta",
            minOf(startEdgeDelta, endEdgeDelta) / density <= 16f,
        )
        assertTrue("volume control must look like a thin progress bar", volumeTrack.height / density <= 8f)

        // Match QueueRowMenu's focusable Popup dismissal contract.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("player-dac-volume-panel", useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag("player-bottom-output").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true).assertIsDisplayed()
        val outsideX = rootScreen.x + 8f * density
        val outsideY = rootScreen.y + rootSize.height * 0.5f
        val downTime = SystemClock.uptimeMillis()
        val outsideDown = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, outsideX, outsideY, 0,
        )
        val outsideUp = MotionEvent.obtain(
            downTime, downTime + 60L, MotionEvent.ACTION_UP, outsideX, outsideY, 0,
        )
        try {
            instrumentation.uiAutomation.injectInputEvent(outsideDown, true)
            instrumentation.uiAutomation.injectInputEvent(outsideUp, true)
        } finally {
            outsideDown.recycle()
            outsideUp.recycle()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("player-dac-volume-panel", useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag("player-bottom-output").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("player-bottom-output").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("player-dac-volume-panel", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun compactPanelWidthExpandsOnlyWhenFreshOutputFieldsNeedMoreSpace() {
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, Rect(0f, 0f, 1080f, 2400f))
            update(PopupMotionAnchorKind.TRIGGER, Rect(510f, 1700f, 570f, 1760f))
        }
        val minimalOutput = OutputPresentation(
            observationStatus = OutputObservationStatus.FRESH,
            dacStatus = DacOutputStatus.UNKNOWN,
            dacFormat = null,
            dacSampleRateHz = null,
            dacBitDepth = null,
            dacChannels = null,
            errorCode = null,
            errorMessage = null,
            diagnostics = emptyList(),
        )
        val outputState = mutableStateOf(minimalOutput)
        val capability = HardwareVolumeCapability(
            status = HardwareVolumeCapabilityStatus.UNKNOWN,
            displayVolumePercent = null,
            controllableVolumePercent = null,
            canWrite = false,
            diagnostics = emptySet(),
            decodeDiagnostics = emptyList(),
        )

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.LIGHT) {
                CompactDacPanelPopup(
                    output = outputState.value,
                    hardwareVolume = capability,
                    visible = true,
                    onDismiss = {},
                    anchorState = anchor,
                )
            }
        }
        composeRule.waitForIdle()
        val compactWidth = composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width

        composeRule.runOnIdle {
            outputState.value = minimalOutput.copy(
                dacStatus = DacOutputStatus.ACTIVE,
                dacFormat = "pcm",
                dacSampleRateHz = 192000,
                dacBitDepth = 24,
                dacChannels = 2,
            )
        }
        composeRule.waitForIdle()
        val detailedWidth = composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue("sparse output information should produce a shorter panel", detailedWidth > compactWidth + 40f)
    }

    @Test
    fun staleDacOutputMetadataIsNeverPresentedAsCurrentOutput() {
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, Rect(0f, 0f, 1080f, 2400f))
            update(PopupMotionAnchorKind.TRIGGER, Rect(510f, 1700f, 570f, 1760f))
        }
        val output = OutputPresentation(
            observationStatus = OutputObservationStatus.STALE,
            dacStatus = DacOutputStatus.STALE,
            dacFormat = "pcm",
            dacSampleRateHz = 96000,
            dacBitDepth = 24,
            dacChannels = 2,
            errorCode = null,
            errorMessage = null,
            diagnostics = emptyList(),
        )
        val capability = HardwareVolumeCapability(
            status = HardwareVolumeCapabilityStatus.UNKNOWN,
            displayVolumePercent = null,
            controllableVolumePercent = null,
            canWrite = false,
            diagnostics = emptySet(),
            decodeDiagnostics = emptyList(),
        )

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.LIGHT) {
                CompactDacPanelPopup(
                    output = output,
                    hardwareVolume = capability,
                    visible = true,
                    onDismiss = {},
                    anchorState = anchor,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dac-output-info").assertIsDisplayed()
        composeRule.onNodeWithTag("dac-output-format").assertTextEquals("DAC")
        composeRule.onAllNodesWithText("PCM").assertCountEquals(0)
        composeRule.onAllNodesWithText("96 kHz").assertCountEquals(0)
        composeRule.onAllNodesWithText("24 bit").assertCountEquals(0)
        composeRule.onAllNodesWithText("2 ch").assertCountEquals(0)
        composeRule.onNodeWithText("已过期").assertIsDisplayed()
    }

    @Test
    fun compactPanelKeepsTwoRowsAndSafeMarginsWithLargerSystemFontScale() {
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, Rect(0f, 0f, 1080f, 2400f))
            update(PopupMotionAnchorKind.TRIGGER, Rect(510f, 1700f, 570f, 1760f))
        }
        val output = OutputPresentation(
            observationStatus = OutputObservationStatus.FRESH,
            dacStatus = DacOutputStatus.ACTIVE,
            dacFormat = "pcm",
            dacSampleRateHz = 192000,
            dacBitDepth = 24,
            dacChannels = 2,
            errorCode = null,
            errorMessage = null,
            diagnostics = emptyList(),
        )
        val capability = HardwareVolumeCapability(
            status = HardwareVolumeCapabilityStatus.UNKNOWN,
            displayVolumePercent = null,
            controllableVolumePercent = null,
            canWrite = false,
            diagnostics = emptySet(),
            decodeDiagnostics = emptyList(),
        )
        val baseDensity = composeRule.activity.resources.displayMetrics.density

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(baseDensity, fontScale = 1.35f)) {
                MpdServerRemoteTheme(themePreference = ThemePreference.LIGHT) {
                    CompactDacPanelPopup(
                        output = output,
                        hardwareVolume = capability,
                        visible = true,
                        onDismiss = {},
                        anchorState = anchor,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dac-output-info").assertIsDisplayed()
        composeRule.onNodeWithTag("dac-volume-row").assertIsDisplayed()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals("—")
        val density = baseDensity
        val panelNode = composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true).fetchSemanticsNode()
        val panel = panelNode.boundsInRoot
        val panelScreen = panelNode.layoutInfo.coordinates.positionOnScreen()
        val panelSize = panelNode.layoutInfo.coordinates.size
        val outputRow = composeRule.onNodeWithTag("dac-output-info").fetchSemanticsNode().boundsInRoot
        val volumeRow = composeRule.onNodeWithTag("dac-volume-row").fetchSemanticsNode().boundsInRoot
        val outputText = composeRule.onNodeWithTag("dac-output-format").fetchSemanticsNode().boundsInRoot
        val root = composeRule.activity.window.decorView
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        val rootWidthDp = root.width / density
        val effectiveMaxWidthDp = minOf(rootWidthDp * 0.92f, 520f, (rootWidthDp - 96f).coerceAtLeast(200f))
        assertTrue("scaled DAC panel remains wide enough for a usable compact volume track", panel.width / density >= 200f)
        assertTrue("scaled DAC panel remains no wider than its safe width policy", panel.width / density <= effectiveMaxWidthDp + 1f)
        assertTrue("scaled panel keeps horizontal safety margins", (panelScreen.x - rootLocation[0]) / density >= 16f)
        assertTrue("scaled panel keeps horizontal safety margins", (rootLocation[0] + root.width - panelScreen.x - panelSize.width) / density >= 16f)
        assertTrue("DAC information remains a single bounded row", outputRow.height / density <= 40f)
        assertTrue("volume remains a separate second row", volumeRow.top >= outputRow.bottom)
        assertTrue("output text remains inside its row under font scaling", outputText.top >= outputRow.top && outputText.bottom <= outputRow.bottom)
    }

    @Test
    fun compactPanelDisplaysConfirmedDigitsOmitsUnknownFieldsAndMapsOutputStates() {
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, Rect(0f, 0f, 1080f, 2400f))
            update(PopupMotionAnchorKind.TRIGGER, Rect(510f, 1700f, 570f, 1760f))
        }
        val output = OutputPresentation(
            observationStatus = OutputObservationStatus.FRESH,
            dacStatus = DacOutputStatus.INACTIVE,
            dacFormat = null,
            dacSampleRateHz = null,
            dacBitDepth = null,
            dacChannels = null,
            errorCode = null,
            errorMessage = null,
            diagnostics = emptyList(),
        )
        val outputState = mutableStateOf(output)
        val capability = HardwareVolumeCapability(
            status = HardwareVolumeCapabilityStatus.AVAILABLE,
            displayVolumePercent = 72,
            controllableVolumePercent = 72,
            canWrite = true,
            diagnostics = emptySet(),
            decodeDiagnostics = emptyList(),
            canonicalTuple = testHardwareVolumeTuple(72),
        )
        val capabilityState = mutableStateOf(capability)
        val themePreference = mutableStateOf(ThemePreference.LIGHT)

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = themePreference.value) {
                CompactDacPanelPopup(
                    output = outputState.value,
                    hardwareVolume = capabilityState.value,
                    visible = true,
                    onDismiss = {},
                    anchorState = anchor,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("DAC").assertIsDisplayed()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals("72")
        composeRule.onAllNodesWithText("72%").assertCountEquals(0)
        composeRule.onNodeWithTag("dac-volume-slider").assertIsEnabled()
        val confirmedSliderSemantics = composeRule.onNodeWithTag("dac-volume-slider").fetchSemanticsNode().config.toString()
        assertTrue("slider accessibility should describe the server-confirmed value", confirmedSliderSemantics.contains("服务端确认值 72"))
        assertTrue("an available slider must expose an integer 0..100 control range", confirmedSliderSemantics.contains("ProgressBarRangeInfo"))
        composeRule.runOnIdle { themePreference.value = ThemePreference.DARK }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-dac-volume-panel", useUnmergedTree = true).assertIsDisplayed()

        val unknownCapability = capability.copy(
            status = HardwareVolumeCapabilityStatus.UNKNOWN,
            displayVolumePercent = null,
            controllableVolumePercent = null,
            canWrite = false,
        )
        composeRule.runOnIdle { capabilityState.value = unknownCapability }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals("—")
        composeRule.onAllNodesWithText("0").assertCountEquals(0)
        val unknownSliderSemantics = composeRule.onNodeWithTag("dac-volume-slider").fetchSemanticsNode().config.toString()
        assertTrue("unknown hardware volume must be announced as unknown", unknownSliderSemantics.contains("数值未知"))
        assertTrue("unknown hardware volume must not be exposed as a zero-valued slider", !unknownSliderSemantics.contains("ProgressBarRangeInfo"))

        val statusLabels = listOf(
            DacOutputStatus.ACTIVE to "已启用",
            DacOutputStatus.INACTIVE to "未启用",
            DacOutputStatus.UNAVAILABLE to "不可用",
            DacOutputStatus.STALE to "已过期",
            DacOutputStatus.UNKNOWN to "未知",
            DacOutputStatus.ERROR to "错误",
        )
        for ((status, label) in statusLabels) {
            composeRule.runOnIdle { outputState.value = output.copy(dacStatus = status) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("dac-output-status").assertTextEquals(label)
        }
    }

    @Test
    fun sliderSubmitsOneAbsoluteTargetOnReleaseAndWaitsForCanonicalReadback() {
        val anchor = PopupMotionAnchorState().apply {
            update(PopupMotionAnchorKind.OWNER, Rect(0f, 0f, 1080f, 2400f))
            update(PopupMotionAnchorKind.TRIGGER, Rect(510f, 1700f, 570f, 1760f))
        }
        val tuple = testHardwareVolumeTuple(80)
        val capabilityState = mutableStateOf(
            HardwareVolumeCapability(
                status = HardwareVolumeCapabilityStatus.AVAILABLE,
                displayVolumePercent = 80,
                controllableVolumePercent = 80,
                canWrite = true,
                diagnostics = emptySet(),
                decodeDiagnostics = emptyList(),
                canonicalTuple = tuple,
            ),
        )
        val commandState = mutableStateOf(HardwareVolumeCommandState())
        val committedTargets = mutableListOf<Int>()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.LIGHT) {
                CompactDacPanelPopup(
                    output = testPresentation().output,
                    hardwareVolume = capabilityState.value,
                    visible = true,
                    onDismiss = {},
                    anchorState = anchor,
                    commandState = commandState.value,
                    onVolumeCommit = { target ->
                        committedTargets += target
                        commandState.value = HardwareVolumeCommandState(
                            phase = HardwareVolumeCommandPhase.IN_FLIGHT,
                            activeTargetPercent = target,
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val slider = composeRule.onNodeWithTag("dac-volume-slider")
        slider.assertIsEnabled()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals("80")
        slider.performTouchInput {
            down(center.copy(x = center.x * 0.25f))
            moveTo(center.copy(x = center.x * 1.72f), delayMillis = 220L)
            up()
        }
        composeRule.waitForIdle()

        assertEquals("one completed gesture must enqueue exactly one absolute intent", 1, committedTargets.size)
        val target = committedTargets.single()
        assertTrue("slider target must be an integer inside the server range", target in 0..100)
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals("80")
        composeRule.onNodeWithTag("dac-volume-pending").assertIsDisplayed()
        val pendingSemantics = slider.fetchSemanticsNode().config.toString()
        assertTrue("pending presentation must keep the confirmed readback explicit", pendingSemantics.contains("服务端确认值 80"))

        composeRule.runOnIdle {
            capabilityState.value = capabilityState.value.copy(
                displayVolumePercent = target,
                controllableVolumePercent = target,
                canonicalTuple = tuple.copy(volumePercent = target),
            )
            commandState.value = HardwareVolumeCommandState()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals(target.toString())
        composeRule.onAllNodesWithTag("dac-volume-pending").assertCountEquals(0)

        // TalkBack/semantics progress changes use the same release callback and gateway seam.
        slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("accessibility must be able to set an integer Slider target", setProgress(33f))
        }
        composeRule.waitForIdle()
        assertEquals("one accessibility adjustment must enqueue one more absolute intent", 2, committedTargets.size)
        assertEquals(33, committedTargets.last())
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals(target.toString())
        composeRule.onNodeWithTag("dac-volume-pending").assertIsDisplayed()

        composeRule.runOnIdle {
            commandState.value = HardwareVolumeCommandState(
                phase = HardwareVolumeCommandPhase.FAILED,
                message = "server rejected volume target",
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals(target.toString())
        composeRule.onNodeWithTag("dac-volume-error").assertIsDisplayed()

        composeRule.runOnIdle {
            commandState.value = HardwareVolumeCommandState(
                phase = HardwareVolumeCommandPhase.UNKNOWN,
                message = "write outcome unknown; retry original intent",
            )
        }
        composeRule.waitForIdle()
        slider.assertIsNotEnabled()
        composeRule.onNodeWithTag("dac-volume-value").assertTextEquals(target.toString())
        composeRule.onNodeWithTag("dac-volume-unknown").assertIsDisplayed()
    }

    private fun testHardwareVolumeTuple(volume: Int) = HardwareVolumeCanonicalTuple(
        epoch = "epoch-a",
        connectionGeneration = 1,
        snapshotConnectionGeneration = 1,
        connectionStatus = RuntimeConnectionStatus.CONNECTED,
        snapshotFreshness = SnapshotFreshness.FRESH,
        status = HardwareVolumeCapabilityStatus.AVAILABLE,
        volumePercent = volume,
        stale = false,
        observationFreshness = ObservationFreshnessDto.fresh,
    )

    private fun testPresentation(): PlayerPresentation {
        val song = PlayerSongPresentation(
            songId = "song-test-1",
            title = "Test Song",
            fileUri = "file:///music/test.flac",
            artists = listOf("Test Artist"),
            albumId = "album-server-42",
            album = "Test Album",
            albumArtists = emptyList(),
            trackNumber = 1,
            discNumber = 1,
            durationSeconds = 200.0,
            lyrics = null,
            lyricsFormat = null,
            lyricsSource = null,
            lyricsStatus = LyricsResourceStatus.MISSING,
            rawLyricsStatus = "missing",
            artworkReferenceStatus = ArtworkReferenceStatus.MISSING,
        )
        return PlayerPresentation(
            playbackStatus = ConfirmedPlaybackStatus.PAUSED,
            actualState = null,
            businessPlaybackState = null,
            playbackDiagnostics = emptyList(),
            currentOccurrenceId = "queue-item-test",
            songStatus = SongMetadataStatus.AVAILABLE,
            song = song,
            artworkReferenceStatus = ArtworkReferenceStatus.MISSING,
            sourceFormat = SourceFormatPresentation(
                status = SourceFormatStatus.UNKNOWN,
                codec = null,
                bitDepth = null,
                sampleRateHz = null,
                channelCount = null,
            ),
            output = OutputPresentation(
                observationStatus = OutputObservationStatus.UNKNOWN,
                dacStatus = DacOutputStatus.UNKNOWN,
                dacFormat = null,
                dacSampleRateHz = null,
                dacBitDepth = null,
                dacChannels = null,
                errorCode = null,
                errorMessage = null,
                diagnostics = emptyList(),
            ),
            controls = PlayerControlCapabilities(),
            resourceDiagnostics = emptyList(),
        )
    }
}
