package org.alpha3.launcher.tools.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal actual fun parallelFor(n: Int, body: (Int) -> Unit) {
    for (i in 0 until n) body(i)
}

internal actual fun decodeEncodedImage(bytes: ByteArray): Bitmap? = try {
    val image = Image.makeFromEncoded(bytes)
    val info = ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    val bmp = org.jetbrains.skia.Bitmap()
    bmp.allocPixels(info)
    val pixels = if (image.readPixels(bmp)) bmp.readPixels(info, image.width * 4) else null
    pixels?.let { px ->
        // BGRA bytes in memory are 0xAARRGGBB little-endian ints
        Bitmap(image.width, image.height, IntArray(image.width * image.height) { i ->
            val o = i * 4
            (px[o].toInt() and 255) or ((px[o + 1].toInt() and 255) shl 8) or ((px[o + 2].toInt() and 255) shl 16) or ((px[o + 3].toInt() and 255) shl 24)
        })
    }
} catch (e: Exception) {
    null
}

internal actual fun argbToImageBitmap(width: Int, height: Int, pixels: IntArray): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = (p shr 16).toByte()
        bytes[i * 4 + 3] = (p shr 24).toByte()
    }
    return Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, width * 4).toComposeImageBitmap()
}

internal actual class FramePresenter actual constructor(private val width: Int, private val height: Int) {
    actual fun present(pixels: IntArray): ImageBitmap =
        argbToImageBitmap(width, height, IntArray(pixels.size) { pixels[it] or -16777216 })
}
