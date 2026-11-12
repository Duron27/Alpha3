package org.alpha3.launcher.benchmark

import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.writeIniValue

/**
 * The fly-through benchmark's tuning loop. A run with no changes is the fixed benchmark (the reset settings, view
 * distance 63545, the screen's own resolution): that one is for comparing builds and renderers. After a run the launcher
 * shows its results with these settings to try, the biggest gain first (as measured on the SM-T720, HANDOFF.md); a run
 * with some of them changed is set beside the run before it. The player's own settings.cfg is only changed by "Apply".
 */
object FlyTuning {
    /** The benchmark's config and user-data folder (FlyBenchmark on Android). */
    val folder: String get() = "${OpenMWPaths.USER_FILE_STORAGE}/benchmarks/profile"
    private val pendingFile get() = File("$folder/pending_result.txt")
    private val tuningFile get() = File("$folder/tuning.cfg")

    const val RESOLUTION = "Video/resolution" // stands for both resolution x and y: a share of the screen's size

    class Option(val label: String, val value: String)

    /** One setting to try. [key] is "Section/key"; the first option is the benchmark's own value. */
    class Tunable(val key: String, val title: String, val why: String, val options: List<Option>)

    val tunables = listOf(
        Tunable(
            RESOLUTION, "Resolution",
            "The biggest gain on most phones: fewer pixels to draw and to move through memory every frame",
            listOf(Option("Screen's own", "1.0"), Option("75%", "0.75"), Option("50%", "0.5")),
        ),
        Tunable(
            "Camera/viewing distance", "View distance",
            "The frame is bound by how many triangles are drawn, and most are far away",
            listOf(Option("63545", "63545"), Option("32768", "32768"), Option("16384", "16384"), Option("8192", "8192")),
        ),
        Tunable(
            "Water/reflection detail", "Water reflection",
            "The reflection draws the scene a second time (12.8 ms of 73 on the tablet)",
            listOf(Option("Terrain and statics (2)", "2"), Option("Terrain only (1)", "1"), Option("Sky only (0)", "0")),
        ),
        Tunable(
            "Terrain/object paging min size", "Distant objects",
            "Leaves out small objects far away: fewer far triangles",
            listOf(Option("All (0.01)", "0.01"), Option("Fewer (0.025)", "0.025"), Option("Few (0.05)", "0.05")),
        ),
        Tunable(
            "Experimental/render scale", "Render scale",
            "Draws the 3D scene smaller and upscales it; costs an upscaling pass, so lower the resolution first",
            listOf(Option("1.0", "1.0"), Option("0.75", "0.75"), Option("0.5", "0.5")),
        ),
        Tunable(
            "Groundcover/enabled", "Groundcover",
            "Grass and small plants near the player",
            listOf(Option("On", "true"), Option("Off", "false")),
        ),
    )

    /** The changes chosen for the next run, "Section/key" to value; empty for the fixed benchmark. */
    fun loadTuning(): Map<String, String> = runCatching {
        tuningFile.takeIf { it.exists() }?.readText()?.lines()
            ?.mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            ?.toMap()
    }.getOrNull().orEmpty()

    fun saveTuning(tuning: Map<String, String>) {
        runCatching {
            File(folder).mkdirs()
            tuningFile.writeText(tuning.entries.joinToString("\n") { "${it.key} = ${it.value}" })
        }
    }

    /** The results to show when the launcher opens after a run (written by EngineActivity), null when none. */
    fun pendingResult(): File? = runCatching {
        pendingFile.takeIf { it.exists() }?.readText()?.trim()?.let { File(it) }?.takeIf { it.exists() }
    }.getOrNull()

    fun setPending(resultTxt: String) {
        runCatching { pendingFile.writeText(resultTxt) }
    }

    fun clearPending() {
        runCatching { pendingFile.delete() }
    }

    /** The average fps of a run's .txt ("data\toverall\t<fps>"). */
    fun overallFps(result: File): Double? = runCatching {
        result.readText().lines().firstOrNull { it.startsWith("data\toverall\t") }?.substringAfterLast('\t')?.toDoubleOrNull()
    }.getOrNull()

    /** The table part of a run's .txt (without its data lines). */
    fun table(result: File): String = runCatching {
        result.readText().lines().filterNot { it.startsWith("data\t") }.joinToString("\n").trim()
    }.getOrDefault("")

    /**
     * The settings lines for a run: [nativeWidth] x [nativeHeight] scaled by the resolution choice, the rest as chosen.
     * Applied by FlyBenchmark on top of the reset settings.
     */
    fun settingValues(tuning: Map<String, String>, nativeWidth: Int, nativeHeight: Int): Map<String, String> {
        val scale = tuning[RESOLUTION]?.toDoubleOrNull() ?: 1.0
        val out = linkedMapOf(
            "Video/resolution x" to (nativeWidth * scale).toInt().toString(),
            "Video/resolution y" to (nativeHeight * scale).toInt().toString(),
        )
        tuning.filterKeys { it != RESOLUTION }.forEach { (k, v) -> out[k] = v }
        return out
    }

    /** Writes the chosen changes into the player's own settings.cfg (the active profile's). */
    fun applyToPlayer(tuning: Map<String, String>, nativeWidth: Int, nativeHeight: Int) {
        val values = settingValues(tuning, nativeWidth, nativeHeight)
            .filterKeys { !it.startsWith("Video/resolution") || RESOLUTION in tuning }
        values.forEach { (key, value) ->
            val (section, name) = key.split("/", limit = 2)
            writeIniValue(section, name, value)
        }
    }
}
