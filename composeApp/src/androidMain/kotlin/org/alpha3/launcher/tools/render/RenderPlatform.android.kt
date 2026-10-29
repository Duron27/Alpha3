package org.alpha3.launcher.tools.render

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Kept for the life of the app: starting threads for every frame cost more than some of the work they did
private val renderPool: ExecutorService by lazy {
    Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)) { r ->
        Thread(r, "alpha3-render").apply { isDaemon = true }
    }
}

internal actual fun parallelFor(n: Int, body: (Int) -> Unit) {
    if (n <= 1) {
        for (i in 0 until n) body(i)
        return
    }
    val workers = (1 until n).map { i -> renderPool.submit { body(i) } }
    body(0)
    workers.forEach { it.get() }
}

internal actual class FramePresenter actual constructor(private val width: Int, private val height: Int) {
    private val bitmaps = Array(2) { android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888) }
    private val images = bitmaps.map { it.asImageBitmap() }
    private val scratch = IntArray(width * height)
    private var next = 0

    actual fun present(pixels: IntArray): ImageBitmap {
        for (i in scratch.indices) scratch[i] = pixels[i] or -16777216
        val i = next
        next = 1 - next
        bitmaps[i].setPixels(scratch, 0, width, 0, 0, width, height)
        return images[i]
    }
}

internal actual fun decodeEncodedImage(bytes: ByteArray): Bitmap? {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return Bitmap(bmp.width, bmp.height, pixels).also { bmp.recycle() }
}

internal actual fun argbToImageBitmap(width: Int, height: Int, pixels: IntArray): ImageBitmap =
    android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
