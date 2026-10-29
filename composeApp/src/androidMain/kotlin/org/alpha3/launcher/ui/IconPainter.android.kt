package org.alpha3.launcher.ui.view

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil3.compose.rememberAsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest
import org.alpha3.launcher.utils.Uri

@Composable
actual fun rememberIconPainter(uri: Uri): Painter {
    val context = LocalContext.current
    val uriString = uri.toString()
    val animated = uriString.endsWith(".gif", ignoreCase = true) ||
        uriString.endsWith(".apng", ignoreCase = true) ||
        // APNGs often use .png extension; ImageDecoder on API 28+ plays them
        (uriString.endsWith(".png", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    return if (animated) {
        rememberAsyncImagePainter(
            ImageRequest.Builder(context)
                .data(uriString)
                .decoderFactory(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AnimatedImageDecoder.Factory()
                    else GifDecoder.Factory()
                )
                .build()
        )
    } else {
        rememberAsyncImagePainter(uriString)
    }
}
