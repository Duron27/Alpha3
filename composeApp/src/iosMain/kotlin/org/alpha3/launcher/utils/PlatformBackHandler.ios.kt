package org.alpha3.launcher.utils

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; screens close through their own controls.
}
