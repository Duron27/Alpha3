package org.alpha3.launcher.ui.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.rememberAsyncImagePainter
import org.alpha3.launcher.utils.Uri

// The iOS app container path contains a space ("Application Support"), which has to be percent-encoded
// for Coil to parse the file URL.
@Composable
actual fun rememberIconPainter(uri: Uri): Painter {
    val raw = uri.toString()
    val encoded = if (raw.startsWith("file://")) {
        "file://" + raw.removePrefix("file://").replace("%", "%25").replace(" ", "%20").replace("#", "%23")
    } else raw
    return rememberAsyncImagePainter(encoded)
}
