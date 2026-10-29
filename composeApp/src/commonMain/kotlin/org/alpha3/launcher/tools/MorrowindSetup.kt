package org.alpha3.launcher.tools

import okio.FileSystem
import org.alpha3.launcher.io.File
import org.alpha3.launcher.io.systemFileSystem
import org.alpha3.launcher.paths.OpenMWPaths

/**
 * Points OpenMW at a Morrowind install. Shared by Android and iOS.
 *
 * Two configs are involved:
 *  - the **global** config (`OpenMWPaths.OPENMW_CFG`, `files/config/openmw.cfg` on Android) belongs to the app
 *    and is recreated on every install. It gets the settings of `Morrowind.ini`, imported like
 *    `openmw-iniimporter` does (`fallback=`, `encoding=`, `no-sound=`), plus the app's `resources`, `data` and
 *    `data-local` paths;
 *  - the **user** config (`OpenMWPaths.USER_OPENMW_CFG`) survives an uninstall. It gets the path to the game's
 *    `Data Files`, every plugin found there as `content=` and every archive as `fallback-archive=`.
 *
 * Nothing in either file is removed. A line is never added twice, a line the user disabled with `;` stays
 * disabled, and a line that is in both files is disabled in the global one ([disableDuplicatesInGlobal]).
 * On iOS both configs are the same file, which everything below copes with.
 */
object MorrowindSetup {

    private val PLUGIN_EXTENSIONS = setOf("esm", "esp", "esl", "omwaddon", "omwgame", "omwscripts")
    private val OFFICIAL = listOf("morrowind", "tribunal", "bloodmoon")
    private const val PLACEHOLDER_DATA = "data=\"specify-me!\""
    private const val PLACEHOLDER_RESOURCES = "resources=./resources"

    /**
     * Run before the engine starts. Cheap when there is nothing to do.
     *  1. If the global config has not been set up yet (a fresh install, while the user config survived an
     *     uninstall), find the game folder and run [setUp]. The folder comes from the user config's `data=`
     *     lines, or from [savedGameFolder] if those do not lead to a Morrowind install.
     *  2. Disable lines of the global config that the user config also has.
     *
     * @return true if [setUp] had to run.
     */
    fun ensureReady(
        savedGameFolder: String? = null,
        globalCfg: String = OpenMWPaths.OPENMW_CFG,
        userCfg: String = OpenMWPaths.USER_OPENMW_CFG,
        resourcesDir: String = OpenMWPaths.USER_RESOURCES,
        log: (String) -> Unit = {},
    ): Boolean {
        var ranSetUp = false
        if (needsSetUp(globalCfg)) {
            val game = findGameFolder(userCfg)
                ?: savedGameFolder?.trimEnd('/')?.takeIf { isGameFolder(it) }
            if (game == null) {
                log("Global config needs the Morrowind.ini import but no game folder is known")
            } else {
                log("Global config is not set up, importing Morrowind.ini from $game")
                ranSetUp = setUp(game, globalCfg = globalCfg, userCfg = userCfg, log = log) == 0
            }
        }
        ensureMultiplayerMod(globalCfg, resourcesDir)
        disableDuplicatesInGlobal(globalCfg, userCfg)
        return ranSetUp
    }

    private const val MULTIPLAYER_CONTENT = "content=mp.omwscripts"

    /**
     * A multiplayer build ships the mod as `resources/vfs-mp`. Then the global config must list it as a data
     * directory and enable its scripts; otherwise (no mod in this build) those lines must not be there, as OpenMW
     * refuses a `content=` file it cannot find. Runs on every start so an update in either direction is picked up.
     */
    fun ensureMultiplayerMod(globalCfg: String, resourcesDir: String = OpenMWPaths.USER_RESOURCES) {
        if (!File(globalCfg).exists()) return
        val modDir = "$resourcesDir/vfs-mp"
        val dataLine = "data=$modDir"
        val isModLine = { line: String -> (line.startsWith("data=") && "vfs-mp" in line) || line == MULTIPLAYER_CONTENT }
        val lines = readLines(globalCfg)
        val updated = if (File(modDir).isDirectory) {
            // keep both lines current (the resources folder can move), added once
            val kept = lines.filterNot(isModLine)
            val at = kept.indexOfLast { it.startsWith("data=") }.let { if (it == -1) kept.size else it + 1 }
            kept.toMutableList().apply { addAll(at, listOf(dataLine, MULTIPLAYER_CONTENT)) }
        } else {
            lines.filterNot(isModLine)
        }
        if (updated != lines) writeLines(globalCfg, updated)
    }

    /** @return 0 on success, see [IniImportRunner.run] for the error codes. */
    fun setUp(
        gameFilesPath: String,
        globalCfg: String = OpenMWPaths.OPENMW_CFG,
        userCfg: String = OpenMWPaths.USER_OPENMW_CFG,
        resourcesDir: String = OpenMWPaths.USER_RESOURCES,
        overrideDir: String = "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Override",
        fs: FileSystem = systemFileSystem,
        log: (String) -> Unit = {},
    ): Int {
        val game = gameFilesPath.trimEnd('/')
        applyPaths(game, globalCfg, userCfg, resourcesDir, overrideDir)
        ensureMultiplayerMod(globalCfg, resourcesDir)

        // Morrowind.ini settings into the global config. Plugins and archives are the user config's job below.
        val result = IniImportRunner.run(
            game, globalCfg,
            importContent = false, importArchives = false,
            keepComments = globalCfg == userCfg, // shared file: keep the '#' comments
            fs = fs, log = log,
        )
        if (result != 0) return result

        addDetectedFiles(game, userCfg, fs)
        disableDuplicatesInGlobal(globalCfg, userCfg)
        return 0
    }

    // region Global config state

    /** True while the global config is still the shipped template, or has no Morrowind.ini settings. */
    fun needsSetUp(globalCfg: String): Boolean {
        if (!File(globalCfg).exists()) return true
        val lines = readLines(globalCfg)
        return PLACEHOLDER_DATA in lines || PLACEHOLDER_RESOURCES in lines ||
            lines.none { it.startsWith("fallback=") || it.startsWith(";fallback=") }
    }

    /** The Morrowind folder named by a `data=".../Data Files"` line of [userCfg], if it still exists. */
    fun findGameFolder(userCfg: String): String? =
        readLines(userCfg)
            .filter { it.startsWith("data=") }
            .map { it.removePrefix("data=").trim().removeSurrounding("\"") }
            .firstNotNullOfOrNull { dataPath ->
                val dir = File(dataPath.trimEnd('/'))
                dir.parent?.takeIf { dir.name.equals("Data Files", ignoreCase = true) && isGameFolder(it) }
            }

    private fun isGameFolder(path: String) =
        File(path, "Morrowind.ini").exists() && File(path, "Data Files").isDirectory

    // endregion

    // region Writing the configs

    fun applyPaths(gameFilesPath: String, globalCfg: String, userCfg: String, resourcesDir: String, overrideDir: String) {
        val dataLine = "data=\"$gameFilesPath/Data Files\""
        val vfsLine = "data=$resourcesDir/vfs-mw"
        val overrideLine = "data-local=$overrideDir"

        // A fresh install may not have the configs yet (iOS ships none). Start them with the paths so the
        // edits below, which only touch existing lines, have something to work on.
        if (!File(globalCfg).exists()) {
            File(globalCfg).parent?.let { File(it).mkdirs() }
            writeLines(globalCfg, listOf("resources=$resourcesDir"))
        }
        if (!File(userCfg).exists()) {
            File(userCfg).parent?.let { File(it).mkdirs() }
            writeLines(userCfg, listOf("# This is the user openmw.cfg. Feel free to modify it as you wish."))
        }

        // Global config. When it is also the user config (iOS) the user's own quoted `data=` lines must stay.
        val sharedFile = globalCfg == userCfg
        if (File(globalCfg).exists()) {
            val lines = readLines(globalCfg).map { line ->
                when {
                    line == PLACEHOLDER_DATA || (!sharedFile && line.startsWith("data=\"")) -> dataLine
                    line == PLACEHOLDER_RESOURCES -> "resources=$resourcesDir"
                    line.startsWith("data=") && "vfs-mw" in line -> vfsLine
                    line.startsWith("data-local=") -> overrideLine
                    else -> line
                }
            }.toMutableList()
            if (lines.none { it.startsWith("data=") && "vfs-mw" in it }) lines.add(vfsLine)
            if (lines.none { it.startsWith("data-local=") }) lines.add(overrideLine)
            writeLines(globalCfg, lines)
        }

        // User config: make sure the game's data folder is listed.
        if (File(userCfg).exists()) {
            val lines = readLines(userCfg).toMutableList()
            if (dataLine !in lines) {
                val first = lines.indexOfFirst { it.startsWith("data=") }
                lines.add(if (first == -1) 0 else first, dataLine)
            }
            writeLines(userCfg, lines)
        }
    }

    /**
     * Adds a `fallback-archive=` for every BSA and a `content=` for every plugin in the game's `Data Files`.
     * Lines already in the config, including ones the user disabled with a leading `;`, are left alone.
     */
    fun addDetectedFiles(gameFilesPath: String, userCfg: String, fs: FileSystem = systemFileSystem) {
        if (!File(userCfg).exists()) return
        val dataFiles = File("$gameFilesPath/Data Files").takeIf { it.isDirectory } ?: File(gameFilesPath)
        val files = dataFiles.listFiles()?.filter { it.isFile }.orEmpty()

        // archives: the three official ones first, then the rest by name
        val archives = files.filter { it.extension.equals("bsa", true) }
            .sortedWith(compareBy({ officialRank(it.nameWithoutExtension) }, { it.name.lowercase() }))
            .map { "fallback-archive=${it.name}" }

        // plugins: ordered by timestamp and masters like the ini import does, the official masters first
        val sorted = IniImporter().sortContent(
            files.filter { it.extension.lowercase() in PLUGIN_EXTENSIONS }.map { (it.lastModified() / 1000) to it.absolutePath },
        ) { path ->
            // omwscripts and other files without a TES3 header have no masters
            runCatching { IniImportRunner.readDependencies(path, "win1252", fs) }
                .getOrElse { path.substringAfterLast('/') to emptyList() }
        }
        val official = sorted.filter { it.endsWith(".esm", true) && officialRank(it.substringBeforeLast('.')) < OFFICIAL.size }
            .sortedBy { officialRank(it.substringBeforeLast('.')) }
        val plugins = (official + sorted.filter { it !in official }).map { "content=$it" }

        val lines = readLines(userCfg)
        val known = lines.mapTo(HashSet()) { it.removePrefix(";").trim() }
        val toAdd = (archives + plugins).filter { it !in known }
        if (toAdd.isNotEmpty()) writeLines(userCfg, lines + toAdd)
    }

    /**
     * Disables (`;`) every active line of the global config that is also an active line of the user config, so
     * the two files never both carry it. Run on every launch. Nothing is deleted, except an active duplicate
     * whose disabled copy is already there. Does nothing when both configs are the same file.
     */
    fun disableDuplicatesInGlobal(globalCfg: String, userCfg: String) {
        if (globalCfg == userCfg || !File(globalCfg).exists() || !File(userCfg).exists()) return

        val userLines = readLines(userCfg).filter(::isActiveEntry).toSet()
        if (userLines.isEmpty()) return

        val globalLines = readLines(globalCfg)
        val alreadyDisabled = globalLines.filter { it.startsWith(";") }.toSet()
        var changed = false
        val result = ArrayList<String>(globalLines.size)
        for (line in globalLines) {
            if (line in userLines) {
                changed = true
                if (";$line" !in alreadyDisabled) result.add(";$line")
            } else {
                result.add(line)
            }
        }
        if (changed) writeLines(globalCfg, result)
    }

    // endregion

    private fun isActiveEntry(line: String) = '=' in line && !line.startsWith(";") && !line.startsWith("#")

    private fun officialRank(nameWithoutExtension: String): Int {
        val rank = OFFICIAL.indexOf(nameWithoutExtension.lowercase())
        return if (rank == -1) OFFICIAL.size else rank
    }

    /** Trimmed lines without the blank ones at the end. */
    private fun readLines(path: String): List<String> =
        File(path).takeIf { it.exists() }?.readLines().orEmpty().map { it.trim() }.dropLastWhile { it.isEmpty() }

    private fun writeLines(path: String, lines: List<String>) = File(path).writeText(lines.joinToString("\n") + "\n")
}
