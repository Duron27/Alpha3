@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.TexturePrepassProgress
import org.alpha3.launcher.utils.TexturePrepassResult
import org.alpha3.launcher.utils.TexturePrepassRunner
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

private typealias Progress = CFunction<(COpaquePointer?, Int, Int, Int, Int, Int, Int) -> Unit>
private typealias Run = CFunction<(CPointer<CPointerVar<ByteVar>>?, Int, CPointer<ByteVar>?, CPointer<ByteVar>?, Int, CPointer<Progress>?, COpaquePointer?) -> Int>
private typealias Cancel = CFunction<() -> Unit>

/**
 * The launcher's texture pre-pass on iOS: Android's libalpha3_texconv as libalpha3_texconv.framework, opened here when
 * it is first used (cpp/openmw-upstream/support/texconv). It fills the cache the engine reads, [OpenMWPaths.ENGINE_CACHE].
 */
object IosTexturePrepass : TexturePrepassRunner {
    private class Library(val run: CPointer<Run>, val cancel: CPointer<Cancel>)

    private val library: Library? by lazy {
        val path = "${NSBundle.mainBundle.privateFrameworksPath}/libalpha3_texconv.framework/libalpha3_texconv"
        val handle = dlopen(path, RTLD_NOW)
        if (handle == null) {
            println("Texture pre-pass: cannot open $path: ${dlerror()?.toKString()}")
            return@lazy null
        }
        val run = dlsym(handle, "alpha3_texconv_run")?.reinterpret<Run>()
        val cancel = dlsym(handle, "alpha3_texconv_cancel")?.reinterpret<Cancel>()
        if (run == null || cancel == null) null else Library(run, cancel)
    }

    override suspend fun run(onProgress: (TexturePrepassProgress) -> Unit): TexturePrepassResult = withContext(Dispatchers.IO) {
        val lib = library ?: return@withContext TexturePrepassResult.Busy
        // the engine's global openmw.cfg, then the active profile's, as the game reads them
        val configs = listOf(OpenMWPaths.OPENMW_CFG, OpenMWPaths.USER_OPENMW_CFG).filter { File(it).exists() }
        // the encoder spreads each big texture over all cores; a few textures at a time keep the cores busy with small ones
        val workers = (NSProcessInfo.processInfo.activeProcessorCount.toInt() / 2).coerceAtLeast(2)
        val listener = StableRef.create(onProgress)
        try {
            val code = memScoped {
                val paths = allocArrayOf(configs.map { it.cstr.ptr })
                lib.run(
                    paths, configs.size, OpenMWPaths.SETTINGS_FILE.cstr.ptr, OpenMWPaths.ENGINE_CACHE.cstr.ptr, workers,
                    staticCFunction { context, phase, done, total, converted, cached, failed ->
                        context!!.asStableRef<(TexturePrepassProgress) -> Unit>().get()(
                            TexturePrepassProgress(phase == 0, done, total, converted, cached, failed)
                        )
                    },
                    listener.asCPointer(),
                )
            }
            TexturePrepassResult.entries.getOrElse(code) { TexturePrepassResult.Done }
        } finally {
            listener.dispose()
        }
    }

    override fun cancel() {
        library?.cancel?.invoke()
    }
}
