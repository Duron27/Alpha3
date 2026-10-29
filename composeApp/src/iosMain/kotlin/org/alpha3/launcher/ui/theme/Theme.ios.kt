package org.alpha3.launcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null

@Composable
internal actual fun SystemBarsEffect(darkTheme: Boolean) {
    // Status bar styling on iOS is handled by the host view controller.
}
