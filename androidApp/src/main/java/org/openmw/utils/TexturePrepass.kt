package org.openmw.utils

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.TexturePrepassProgress
import org.alpha3.launcher.utils.TexturePrepassResult
import org.alpha3.launcher.utils.TexturePrepassRunner
import java.io.File

/**
 * The texture pre-pass in libalpha3_texconv.so: the stock engine's own conversion code, run over the textures of the
 * openmw.cfg files the engine reads (its global one, then the user's), into the engine's cache ([UpstreamEngine.cacheDir]).
 */
class TexturePrepass(private val context: Context) : TexturePrepassRunner {
    /** Called from native code by name, so the release build's shrinker must not rename it. */
    @Keep
    fun interface Listener {
        @Keep
        fun onProgress(phase: Int, done: Int, total: Int, converted: Int, cached: Int, failed: Int)
    }

    override suspend fun run(onProgress: (TexturePrepassProgress) -> Unit): TexturePrepassResult = withContext(Dispatchers.IO) {
        loaded
        val global = UpstreamEngine.globalPath(context)
        val configs = listOf("$global/files/config/openmw.cfg", OpenMWPaths.USER_OPENMW_CFG).filter { File(it).exists() }
        // the encoder spreads each big texture over all cores; a few textures at a time keep the cores busy with small ones
        val workers = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
        UpstreamEngine.exportCachePaths(context)
        val code = runNative(configs.toTypedArray(), OpenMWPaths.SETTINGS_FILE, UpstreamEngine.cacheDir(context), workers) { phase, done, total, converted, cached, failed ->
            onProgress(TexturePrepassProgress(phase == 0, done, total, converted, cached, failed))
        }
        TexturePrepassResult.entries.getOrElse(code) { TexturePrepassResult.Done }
    }

    override fun cancel() {
        if (libraryLoaded) cancelNative()
    }

    private external fun runNative(configs: Array<String>, settingsFile: String, cacheDir: String, workers: Int, listener: Listener): Int

    private external fun cancelNative()

    private companion object {
        @Volatile var libraryLoaded = false
        val loaded by lazy {
            System.loadLibrary("alpha3_texconv")
            libraryLoaded = true
        }
    }
}
