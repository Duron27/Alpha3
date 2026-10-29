package org.alpha3.launcher.launch

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore

/**
 * Command line options for OpenMW, chosen in the Launch options dialog instead of typed: a switch, plus a value where
 * the option takes one. Whatever else is wanted goes into the free text, which understands quotes, so a path with a
 * space in it works.
 *
 * The catalog is the set of options in apps/openmw/options.cpp of the fork that make sense on a phone. The data
 * directories, content files and --resources are managed by the launcher and are not offered.
 */
object LaunchArguments {
    const val GROUP_START = "Starting the game"
    const val GROUP_SCRIPTS = "Scripts"
    const val GROUP_INPUT = "Input, sound and text"
    const val GROUP_DIAGNOSTICS = "Diagnostics"

    /** Options the launcher always passes itself; a copy in the free text would clash with it. */
    private val managed = setOf("--resources")

    val catalog: List<OptionSpec> = listOf(
        OptionSpec(
            "--skip-menu", GROUP_START, "OpenMW",
            "Go straight into the game without showing the main menu. Add a save game below to continue it, or New game to start a new one.",
            OptionKind.FLAG, "", "menu is shown",
        ),
        OptionSpec(
            "--load-savegame", GROUP_START, "OpenMW",
            "Load this save when the game starts (use together with Skip menu).",
            OptionKind.SAVE, "", "none",
        ),
        OptionSpec(
            "--new-game", GROUP_START, "OpenMW",
            "Run the new game sequence at start-up (needs Skip menu).",
            OptionKind.FLAG, "", "off",
        ),
        OptionSpec(
            "--start", GROUP_START, "OpenMW",
            "The cell a new game starts in, for example \"Seyda Neen\" or \"Balmora\".",
            OptionKind.TEXT, "Seyda Neen", "the normal start cell",
        ),
        OptionSpec(
            "--script-all", GROUP_SCRIPTS, "OpenMW",
            "Compile every script (except dialogue scripts) at start-up, so script errors show in the log straight away.",
            OptionKind.FLAG, "", "off",
        ),
        OptionSpec(
            "--script-all-dialogue", GROUP_SCRIPTS, "OpenMW",
            "Compile every dialogue script at start-up.",
            OptionKind.FLAG, "", "off",
        ),
        OptionSpec(
            "--script-console", GROUP_SCRIPTS, "OpenMW",
            "Enable the functions that only work from the console.",
            OptionKind.FLAG, "", "off",
        ),
        OptionSpec(
            "--script-warn", GROUP_SCRIPTS, "OpenMW",
            "What to do with script compile warnings: 0 ignore them, 1 show them but accept the script, 2 treat them as errors.",
            OptionKind.CHOICE, "1", "1", listOf("0", "1", "2"),
        ),
        OptionSpec(
            "--script-run", GROUP_SCRIPTS, "OpenMW",
            "A file with console commands to run when the game starts.",
            OptionKind.TEXT, "${OpenMWPaths.USER_FILE_STORAGE}/startup.txt", "none",
        ),
        OptionSpec(
            "--no-sound", GROUP_INPUT, "OpenMW",
            "Turn all sound off.",
            OptionKind.FLAG, "", "sound on",
        ),
        OptionSpec(
            "--no-grab", GROUP_INPUT, "OpenMW",
            "Do not capture the mouse cursor.",
            OptionKind.FLAG, "", "captured",
        ),
        OptionSpec(
            "--encoding", GROUP_INPUT, "OpenMW",
            "Character encoding of the game's text: win1250 for Central and Eastern European languages, win1251 for Cyrillic, " +
                    "win1252 for Western European.",
            OptionKind.CHOICE, "win1252", "win1252", listOf("win1250", "win1251", "win1252"),
        ),
        OptionSpec(
            "--activate-dist", GROUP_INPUT, "OpenMW",
            "How far away objects can be activated, in game units. Overrides the game's own distance.",
            OptionKind.NUMBER, "192", "the game's value",
        ),
        OptionSpec(
            "--random-seed", GROUP_DIAGNOSTICS, "OpenMW",
            "Seed of the random number generator, to repeat a run exactly.",
            OptionKind.NUMBER, "1", "random",
        ),
        OptionSpec(
            "--full-diagnostics", GROUP_DIAGNOSTICS, "OpenMW (Vulkan)",
            "Record costly diagnostics for bug reports, GPU breadcrumbs included. Slower.",
            OptionKind.FLAG, "", "off",
        ),
        OptionSpec(
            "--vulkan-validation", GROUP_DIAGNOSTICS, "OpenMW (Vulkan)",
            "Turn on Vulkan validation layers: core checks the API use, sync the synchronization, gpu the shaders. Much slower, " +
                    "and the layers must be installed on the device.",
            OptionKind.CHOICE, "core", "off", listOf("core", "sync", "gpu", "core,sync", "core,sync,gpu"),
        ),
        OptionSpec(
            "--snapshot-at-frame", GROUP_DIAGNOSTICS, "OpenMW",
            "Write a diagnostics snapshot when this frame number is reached.",
            OptionKind.NUMBER, "600", "off",
        ),
    )

    val byName: Map<String, OptionSpec> = catalog.associateBy { it.name }
    val groups: List<String> = catalog.map { it.group }.distinct()

    /** The saved games found in the saves folder, newest first, as name to path. */
    fun scanSaves(): List<Pair<String, String>> {
        val directory = File(OpenMWPaths.USER_SAVES)
        if (!directory.exists() || !directory.isDirectory) return emptyList()
        return directory.walk().filter { it.extension == "omwsave" }
            .sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(".omwsave") to it.absolutePath }
            .toList()
    }

    /** The saved entries; the first time the old argument line is read in as free text. */
    suspend fun load(): Map<String, OptionEntry> {
        val prefs = dataStore.data.first()
        prefs[GameFilesPreferences.LAUNCH_ARGS_KEY]?.let { return OptionCodec.decode(it) }
        val legacy = prefs[GameFilesPreferences.ARG_LINE_KEY]
        if (!legacy.isNullOrBlank()) saveCustom(legacy)
        save(emptyMap())
        return emptyMap()
    }

    suspend fun save(entries: Map<String, OptionEntry>) {
        dataStore.edit { it[GameFilesPreferences.LAUNCH_ARGS_KEY] = OptionCodec.encode(entries) }
    }

    suspend fun loadCustom(): String {
        load() // runs the migration of the old line once
        return dataStore.data.first()[GameFilesPreferences.LAUNCH_ARGS_CUSTOM_KEY].orEmpty()
    }

    suspend fun saveCustom(text: String) {
        dataStore.edit { it[GameFilesPreferences.LAUNCH_ARGS_CUSTOM_KEY] = text }
    }

    /** The arguments for the engine, in the order of the catalog, then the free text; values stay whole, spaces and all. */
    fun toArguments(entries: Map<String, OptionEntry>, custom: String): List<String> {
        val result = ArrayList<String>()
        for (spec in catalog) {
            val entry = entries[spec.name] ?: continue
            if (!entry.enabled) continue
            when (spec.kind) {
                OptionKind.FLAG -> result += spec.name
                else -> if (entry.value.isNotBlank()) {
                    result += spec.name
                    result += entry.value
                }
            }
        }
        // The free text must not repeat what the launcher passes itself
        val tokens = tokenizeArguments(custom)
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.substringBefore('=') in managed) {
                i += if (token.contains('=') || i + 1 >= tokens.size) 1 else 2
            } else {
                result += token
                i++
            }
        }
        return result
    }

    suspend fun effective(): List<String> = toArguments(load(), loadCustom())
}
