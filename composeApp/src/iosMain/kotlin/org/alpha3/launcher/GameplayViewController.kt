package org.alpha3.launcher

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import coil3.PlatformContext
import org.alpha3.launcher.engine.Engine
import kotlinx.coroutines.delay
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.overlay.GameplayScreen
import org.alpha3.launcher.utils.SystemInfo
import kotlin.time.Duration.Companion.milliseconds
import org.alpha3.launcher.ui.theme.OpenMWTheme
import platform.UIKit.UIColor

/**
 * The in-game controls shown in a transparent window above the engine's SDL view; the same Compose UI
 * Android overlays on its SDL surface. Called from Swift once the engine is about to start.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun GameplayViewController() = ComposeUIViewController(configure = { opaque = false }) {
    Engine.bridge = IosEngineBridge()
    remember { if (SystemInfo.services !is IosSystemServices) SystemInfo.services = IosSystemServices() }
    // the engine's frame intervals feed the performance hub (Android: EngineActivity.FrameTimeWatcher)
    LaunchedEffect(UIStateManager.isPerformanceHudEnabled) {
        while (UIStateManager.isPerformanceHudEnabled) {
            UIStateManager.frameTimes.value = IosFrameTimes.read()
            delay(500.milliseconds)
        }
    }
    OpenMWTheme(darkTheme = true) {
        GameplayScreen(
            context = PlatformContext.INSTANCE,
            forwardTouches = true,
            landscapeOnly = true,
            prepare = { installDefaultUi() }
        )
    }
}.also {
    it.view.backgroundColor = UIColor.clearColor
    SystemKeyboard.host = it
}
