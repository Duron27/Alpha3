package org.alpha3.launcher.ui.view

import androidx.compose.runtime.Composable

/**
 * Called inside a Dialog's content: hides the system bars in the dialog's own window, which doesn't inherit the game's
 * immersive mode (on Android the status bar would show over the top of a full screen dialog).
 */
@Composable
expect fun HideDialogSystemBars()
