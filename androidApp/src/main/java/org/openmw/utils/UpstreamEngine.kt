package org.openmw.utils

import android.content.Context
import android.system.Os
import android.util.Log
import org.alpha3.launcher.paths.OpenMWPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.alpha3.launcher.tools.IniImportRunner
import org.alpha3.launcher.tools.MorrowindSetup
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore
import org.openmw.BuildConfig
import java.io.File

/**
 * Where stock OpenMW (libopenmw_upstream.so, cpp/openmw-upstream) keeps what it must not share with the fork: its resources
 * (shaders, Lua API and l10n differ between the two) and its global config (openmw.cfg, defaults.bin). The user's data,
 * saves, settings.cfg and user openmw.cfg stay shared, so one Morrowind setup serves both.
 */
object UpstreamEngine {
    private const val ASSETS = "libopenmw-upstream"
    /** In the config folder: the game folder whose Morrowind.ini was last imported into the global openmw.cfg. */
    private const val INI_STAMP = "ini-imported"

    /** Handed to the engine as its global path: the engine appends "files/config" to it. */
    fun globalPath(context: Context): String = File(context.filesDir, "upstream-engine").absolutePath

    /**
     * The engine's cache (converted ASTC textures, shader programs, data folder listings, the texture preload list) lives
     * on shared storage, so that reinstalling the app does not throw away hours of texture conversion. Earlier builds
     * kept it in the global path; [migrateCache] moves it, and until it has, the engine keeps using the old one.
     */
    private val sharedCacheDir: File get() = File(OpenMWPaths.USER_FILE_STORAGE, "cache")

    private fun privateCacheDir(context: Context) = File(globalPath(context), "cache")

    @Volatile private var migrating = false

    fun cacheDir(context: Context): String =
        (if (migrating || privateCacheDir(context).exists()) privateCacheDir(context) else sharedCacheDir).absolutePath

    /**
     * The environment the engine's native code reads its cache path from (OPENMW_ANDROID_CACHE_PATH), and a folder of
     * the app's own storage for the cache's lock files: shared storage (FUSE) has no file locks.
     */
    fun exportCachePaths(context: Context) {
        val cache = File(cacheDir(context)).apply { mkdirs() }
        val locks = File(context.noBackupFilesDir, "locks").apply { mkdirs() }
        Os.setenv("OPENMW_ANDROID_CACHE_PATH", cache.absolutePath, true)
        Os.setenv("ALPHA3_LOCK_DIR", locks.absolutePath, true)
    }

    /** Moves the cache of an earlier build to shared storage. Several GB: call it off the main thread. */
    fun migrateCache(context: Context) {
        val from = privateCacheDir(context)
        if (!from.exists()) return
        migrating = true
        try {
            Log.d("UpstreamEngine", "Moving the engine cache from ${from.path} to ${sharedCacheDir.path}")
            from.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = File(sharedCacheDir, file.relativeTo(from).path)
                // what this build made there already stays
                if (target.exists()) return@forEach
                target.parentFile?.mkdirs()
                val partial = File(target.path + ".moving")
                file.copyTo(partial, overwrite = true)
                if (!partial.renameTo(target)) partial.delete()
            }
            from.deleteRecursively()
            Log.d("UpstreamEngine", "Engine cache moved")
        } catch (e: Exception) {
            Log.w("UpstreamEngine", "Moving the engine cache failed, trying again on the next start", e)
        } finally {
            migrating = false
        }
    }

    val resourcesDir: String get() = "${OpenMWPaths.USER_FILE_STORAGE}/resources-upstream"

    private fun globalConfigDir(context: Context) = File(globalPath(context), "files/config")

    /** Copies resources and the global config out of the APK when this build is not what was copied last. */
    fun prepare(context: Context) {
        val stamp = File(globalPath(context), "stamp")
        val target = BuildConfig.RANDOMIZER.toString()
        val resources = File(resourcesDir)
        val config = globalConfigDir(context)
        if (stamp.exists() && stamp.readText().trim() == target && resources.exists() && File(config, "defaults.bin").exists()) {
            importMorrowindIni(config)
            ensureMultiplayerMod(config)
            return
        }
        Log.d("UpstreamEngine", "Copying the stock OpenMW resources and config")
        val assets = ManageAssets(context)
        assets.copy("$ASSETS/resources", resourcesDir, overwrite = true)
        config.mkdirs()
        assets.copy("$ASSETS/openmw", config.absolutePath, overwrite = true)
        // The fallback settings are only a template for the user's settings.cfg, not a config file of the engine
        File(config, "settings.fallback.cfg").delete()
        // The global cfg points at "./resources" (relative to where it would be installed); the resources live elsewhere
        val cfg = File(config, "openmw.cfg")
        if (cfg.exists()) cfg.writeText(cfg.readText().replace("./resources", resourcesDir))
        File(config, INI_STAMP).delete()
        importMorrowindIni(config)
        ensureMultiplayerMod(config)
        stamp.parentFile?.mkdirs()
        stamp.writeText(target)
    }

    /**
     * Morrowind.ini's settings (the fallback= values: weather, fonts, ...) into the engine's own global openmw.cfg, as
     * [MorrowindSetup.setUp] does for the fork's. Stock OpenMW's built-in values lack some (Ashstorm and Blight weather:
     * an ash storm's sky had no cloud texture), and this config is copied out of the APK again with every build, so the
     * import runs after each copy and whenever the game folder changes.
     */
    private fun importMorrowindIni(config: File) {
        val game = runBlocking { dataStore.data.first()[GameFilesPreferences.GAME_FILES_URI_KEY] }.orEmpty().trimEnd('/')
        val cfg = File(config, "openmw.cfg")
        val stamp = File(config, INI_STAMP)
        if (game.isEmpty() || !cfg.exists() || (stamp.exists() && stamp.readText() == game)) return
        val result = IniImportRunner.run(game, cfg.absolutePath, importContent = false, importArchives = false) { Log.d("UpstreamEngine", it) }
        if (result == 0) stamp.writeText(game) else Log.w("UpstreamEngine", "Morrowind.ini import into ${cfg.path} failed ($result)")
    }

    /**
     * The engine loads the multiplayer mod from resources-upstream/vfs by itself, so the separate vfs-mp folder and the
     * cfg lines the launcher added for it (an earlier build did) are removed: a script list given twice stops the engine.
     */
    private fun ensureMultiplayerMod(config: File) {
        File(resourcesDir, "vfs-mp").deleteRecursively()
        MorrowindSetup.ensureMultiplayerMod(File(config, "openmw.cfg").absolutePath, resourcesDir)
    }
}
