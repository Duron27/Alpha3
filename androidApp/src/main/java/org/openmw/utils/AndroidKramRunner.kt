package org.openmw.utils

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPathsAndroid
import org.alpha3.launcher.tools.KramTool

/**
 * Runs kram as a library: `libkram.so` is built with KRAM_BUILD_SHARED and exports `kram_main_output`,
 * which [NativeAppLauncher.runWithOutput] calls in this process.
 */
class AndroidKramRunner : KramTool.Runner {
    // Every conversion already uses all cores, so a couple at a time is enough to overlap small textures
    // with a big one.
    override val maxConcurrent: Int = 2

    override suspend fun run(args: List<String>, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        ensureLoaded()
        val code = IntArray(1)
        val output = NativeAppLauncher.runWithOutput("libkram.so", "kram_main_output", (listOf("kram") + args).toTypedArray(), code)
        output.lineSequence().filter { it.isNotEmpty() }.forEach(onLine)
        code[0]
    }

    private companion object {
        // kram writes each result to a temporary file first. Android has no /tmp, so give it the app's cache folder.
        private val prepared by lazy {
            System.loadLibrary("Alpha3")
            Os.setenv("TMPDIR", OpenMWPathsAndroid.appContext.cacheDir.absolutePath, true)
        }
        fun ensureLoaded() = prepared
    }
}
