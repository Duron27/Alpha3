package org.alpha3.launcher.utils

/**
 * Converts the textures of the current mod list for the stock OpenMW engine before it starts, into the cache the engine
 * reads, so the game does not convert them while it loads (Android: libalpha3_texconv.so, cpp/openmw-upstream/support/texconv).
 */
interface TexturePrepassRunner {
    /** [onProgress] is called a few times a second until the run ends. */
    suspend fun run(onProgress: (TexturePrepassProgress) -> Unit): TexturePrepassResult

    fun cancel()
}

data class TexturePrepassProgress(
    /** true while the data directories are listed ([done] and [total] count directories), false while converting. */
    val listing: Boolean,
    val done: Int,
    val total: Int,
    val converted: Int,
    val cached: Int,
    val failed: Int,
)

enum class TexturePrepassResult { Done, Cancelled, ConversionOff, Busy }
