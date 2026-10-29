package org.alpha3.launcher.ui.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.alpha3.launcher.utils.Uri

/** Painter for a user supplied button icon; animated formats (gif/apng) animate where the platform can. */
@Composable
expect fun rememberIconPainter(uri: Uri): Painter
