package org.alpha3.launcher.utils

import androidx.compose.runtime.Composable

/** Intercepts the system back gesture/button while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
