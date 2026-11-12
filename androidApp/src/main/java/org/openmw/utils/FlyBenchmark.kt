package org.openmw.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import org.alpha3.launcher.benchmark.FlyTuning
import org.alpha3.launcher.paths.OpenMWPaths
import org.openmw.EngineActivity
import kotlinx.coroutines.InternalCoroutinesApi
import java.io.File

/**
 * The fly-through benchmark (support/src/benchmark.cpp, OPENMW_BENCHMARK_FLY): the game started in a config folder of
 * its own, so every run is alike whatever the player has changed: the settings the reset button gives
 * (settings.fallback.cfg) with the route's viewing distance, the screen's own resolution and no post-processing, and
 * only the base game (Morrowind, Tribunal, Bloodmoon; no mods). It starts a new game without character creation; the
 * benchmark moves the player to the route's start itself, in first person with nothing in the hands. Frame generation
 * and the Launch options' arguments are left out (EngineActivity).
 */
object FlyBenchmark {
    const val EXTRA_ROUTE = "benchmark_route"
    const val VIEWING_DISTANCE = 63545

    /** Its config and user-data folder; not under Alpha3/config, so it is not listed as a profile. */
    val folder: String get() = FlyTuning.folder

    /** Set by EngineActivity when it was started on a route. */
    @Volatile
    var running = false

    /** The settings.cfg the game runs with: the benchmark's own while one runs (SDLSurface sizes the window by it). */
    @JvmStatic
    fun settingsFile(): String = if (running) "$folder/settings.cfg" else OpenMWPaths.SETTINGS_FILE

    /** Starts the game on [route]; null when started, otherwise why it could not be. */
    @OptIn(InternalCoroutinesApi::class)
    fun start(context: Context, route: String = "vivec", tuning: Map<String, String> = emptyMap()): String? {
        prepare(context, tuning)?.let { return it }
        context.startActivity(Intent(context, EngineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_ROUTE, route)
            // a new game without character creation, in an exterior: the benchmark takes the player to the route
            putExtra("omw_args", "--skip-menu --start \"Seyda Neen\"")
        })
        (context as? Activity)?.finish()
        return null
    }

    /**
     * Writes the benchmark's own config folder: the same for everyone. Called by [start], and again by EngineActivity
     * whenever it is started on a route, so a run started any other way (over adb, by a shortcut) cannot pick up
     * whatever was left in the folder. [tuning] is the tuning loop's chosen changes (FlyTuning). Null when written,
     * otherwise why it could not be.
     */
    @OptIn(InternalCoroutinesApi::class)
    fun prepare(context: Context, tuning: Map<String, String>): String? {
        val openmwCfg = File(OpenMWPaths.USER_OPENMW_CFG)
        if (!openmwCfg.isFile) return "No openmw.cfg: set up the game first"
        val dir = File(folder).apply { mkdirs() }
        val vanilla = vanillaConfig(openmwCfg) ?: return "Morrowind.esm was not found in the game's data folders"
        File(dir, "openmw.cfg").writeText(vanilla)
        val settings = File(dir, "settings.cfg")
        UserManageAssets(context).copySettingsTemplate(settings.path)
        if (!settings.isFile) return "Could not write the benchmark's settings"
        val (width, height) = nativeSize(context)
        // the route's view distance and the screen's size, then the tuning loop's changes (FlyTuning), by section
        val values = linkedMapOf(
            "Camera/viewing distance" to VIEWING_DISTANCE.toString(),
            "Post Processing/enabled" to "false",
        )
        // What this phone needs to show the textures at all, taken from the player's settings: a GPU without BC (DXT)
        // textures draws them white unless they are converted to ASTC, and the cache of converted ones is reused
        val own = readSettings(File(OpenMWPaths.SETTINGS_FILE))
        for (key in listOf("Textures/enable texture conversion", "Textures/astc cache path", "Textures/astc block size per texture"))
            own[key]?.let { values[key] = it }
        // the texture upscaler changes how every texture looks and loads: never in the benchmark
        values["Textures/texture upscale"] = "false"
        values.putAll(FlyTuning.settingValues(tuning, width, height))
        var section = ""
        settings.writeText(settings.readLines().joinToString("\n", postfix = "\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) section = trimmed.substring(1, trimmed.length - 1).trim()
            val key = trimmed.substringBefore("=", "").trim()
            val value = if (key.isNotEmpty() && !trimmed.startsWith("#")) values["$section/$key"] else null
            if (value != null) "$key = $value" else line
        })
        return null
    }

    private val vanillaContent = setOf("morrowind.esm", "tribunal.esm", "bloodmoon.esm")
    private val vanillaArchives = setOf("morrowind.bsa", "tribunal.bsa", "bloodmoon.bsa")

    /**
     * The player's openmw.cfg with only the base game in it: the data folder that holds Morrowind.esm, Morrowind,
     * Tribunal and Bloodmoon and their archives; every other data folder, plugin and groundcover left out. The rest
     * (the imported Morrowind.ini fallback values, the encoding) is kept. Null when Morrowind.esm is not found.
     */
    private fun vanillaConfig(openmwCfg: File): String? {
        fun value(line: String) = line.substringAfter("=").trim().removeSurrounding("\"")
        var baseFound = false
        var baseFiles: Array<File>? = null
        val kept = openmwCfg.readLines().filter { raw ->
            val line = raw.trim()
            val key = line.substringBefore("=", "").trim()
            when (key) {
                "data" -> File(value(line)).listFiles().also { files ->
                    if (files?.any { it.name.equals("Morrowind.esm", true) } == true && baseFiles == null) baseFiles = files
                }?.any { it.name.equals("Morrowind.esm", true) }.also {
                    if (it == true) baseFound = true
                } == true
                "data-local", "groundcover" -> false
                "content" -> value(line).lowercase() in vanillaContent
                "fallback-archive" -> value(line).lowercase() in vanillaArchives
                else -> true
            }
        }
        if (!baseFound) return null
        // the vanilla archives in the base data folder, where the player's openmw.cfg does not list them (an older
        // launcher's config has no fallback-archive lines: without them every mesh and texture is missing). Only
        // archives that are there, and an expansion's only when its .esm is in the content list. The player's own
        // openmw.cfg (the ini importer's) is only read; this goes into the benchmark profile's copy.
        val listed = kept.filter { it.trim().startsWith("fallback-archive") }.map { value(it).lowercase() }.toSet()
        val content = kept.filter { it.trim().startsWith("content") }.map { value(it).lowercase() }.toSet()
        val missing = listOf("Morrowind.bsa", "Tribunal.bsa", "Bloodmoon.bsa").filter { name ->
            name.lowercase() !in listed && baseFiles?.any { it.name.equals(name, true) } == true &&
                name.lowercase().replace(".bsa", ".esm") in content
        }.map { name -> "fallback-archive=" + baseFiles!!.first { it.name.equals(name, true) }.name }
        return (missing + kept).joinToString("\n", postfix = "\n")
    }

    @Volatile
    private var cancelled = false

    /** The run's Cancel button: back to the launcher without results. */
    fun cancel(activity: Activity) {
        cancelled = true
        backToLauncher(activity)
    }

    /** RestartActivity, in its own process, ends this one and starts the launcher in a fresh one. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, InternalCoroutinesApi::class)
    private fun backToLauncher(activity: Activity) {
        activity.startActivity(Intent(activity, RestartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
            putExtra(RestartActivity.EXTRA_TARGET, org.openmw.MainActivity::class.java.name)
        })
    }

    /** A settings.cfg's values as "Section/key" to value. */
    private fun readSettings(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, String>()
        var section = ""
        file.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") && line.endsWith("]") -> section = line.substring(1, line.length - 1).trim()
                line.startsWith("#") || "=" !in line -> {}
                else -> out["$section/${line.substringBefore("=").trim()}"] = line.substringAfter("=").trim()
            }
        }
        return out
    }

    /** The display's full size in pixels, the longer side first (the game runs in landscape). */
    @Suppress("DEPRECATION")
    fun nativeSize(context: Context): Pair<Int, Int> {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        return maxOf(metrics.widthPixels, metrics.heightPixels) to minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * EngineActivity, on a route: once the run has written its results, leaves them on the screen for a few seconds, then
     * opens the launcher (which shows them with the settings to try) and ends the game. The game's process cannot start
     * the engine twice, so RestartActivity (a process of its own) ends it and starts the launcher in a new one.
     */
    @OptIn(InternalCoroutinesApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
    fun returnToLauncherWhenDone(activity: Activity) {
        val startedAt = System.currentTimeMillis()
        val dir = File("${OpenMWPaths.USER_FILE_STORAGE}/benchmarks")
        Thread {
            while (true) {
                Thread.sleep(2000)
                val result = dir.listFiles { f -> f.name.startsWith("flythrough-") && f.name.endsWith(".txt") && f.lastModified() >= startedAt }
                    ?.maxByOrNull { it.lastModified() } ?: continue
                Thread.sleep(5000)
                FlyTuning.setPending(result.path)
                if (cancelled) return@Thread
                activity.runOnUiThread { backToLauncher(activity) }
                return@Thread
            }
        }.apply { isDaemon = true; name = "FlyBenchmarkWatch" }.start()
    }
}
