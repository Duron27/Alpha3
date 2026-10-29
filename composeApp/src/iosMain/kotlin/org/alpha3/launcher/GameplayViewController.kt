package org.alpha3.launcher

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import coil3.PlatformContext
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.ui.overlay.GameplayScreen
import org.alpha3.launcher.ui.theme.OpenMWTheme
import platform.UIKit.UIColor

/**
 * The in-game controls shown in a transparent window above the engine's SDL view; the same Compose UI
 * Android overlays on its SDL surface. Called from Swift once the engine is about to start.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun GameplayViewController() = ComposeUIViewController(configure = { opaque = false }) {
    Engine.bridge = IosEngineBridge()
    OpenMWTheme(darkTheme = true) {
        GameplayScreen(
            context = PlatformContext.INSTANCE,
            forwardTouches = true,
            landscapeOnly = true,
            showLogToggle = true,
            prepare = { installDefaultUi() }
        )
    }
}.also {
    it.view.backgroundColor = UIColor.clearColor
    SystemKeyboard.host = it
}
