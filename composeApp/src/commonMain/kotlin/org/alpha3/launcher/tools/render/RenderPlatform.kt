package org.alpha3.launcher.tools.render

import androidx.compose.ui.graphics.ImageBitmap

/** Runs [body] for 0 until [n], on several threads where the platform has them; the calls must not touch the same data. */
internal expect fun parallelFor(n: Int, body: (Int) -> Unit)

/** Decodes a PNG or JPEG (the formats the platform's own decoder reads), or null if it cannot. */
internal expect fun decodeEncodedImage(bytes: ByteArray): Bitmap?

/** Wraps ARGB pixels as an image Compose can draw. */
internal expect fun argbToImageBitmap(width: Int, height: Int, pixels: IntArray): ImageBitmap

/**
 * Hands rendered frames to Compose without allocating a new image each frame where the platform allows it: two images
 * are filled in turn, so the one on screen is never the one being written, and each frame is a new state value.
 */
internal expect class FramePresenter(width: Int, height: Int) {
    /** The frame in [pixels] (ARGB) as an opaque image. */
    fun present(pixels: IntArray): ImageBitmap
}
