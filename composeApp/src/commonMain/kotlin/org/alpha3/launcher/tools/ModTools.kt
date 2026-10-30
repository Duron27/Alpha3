package org.alpha3.launcher.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.coverify.CoverifyConfig
import org.alpha3.launcher.tools.coverify.CoverifyOptions
import org.alpha3.launcher.tools.coverify.runCoverify
import org.alpha3.launcher.tools.merge.Alpha3MergeOptions
import org.alpha3.launcher.tools.merge.DEFAULT_SKIPPED_TYPES
import org.alpha3.launcher.tools.merge.MERGEABLE_TYPES
import org.alpha3.launcher.tools.merge.runAlpha3Merge
import org.alpha3.launcher.tools.lightfixes.LightFixesOptions
import org.alpha3.launcher.tools.lightfixes.runLightFixes
import org.alpha3.launcher.tools.navmesh.KotlinNavMesh
import org.alpha3.launcher.tools.openmw.PruneReport
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.deleteOverriddenFiles
import org.alpha3.launcher.tools.openmw.disableMissingConfigEntries
import org.alpha3.launcher.tools.openmw.findOverriddenFiles
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolNotFound
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.Vfs
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.tools.tes3cmd.CleanOptions
import org.alpha3.launcher.tools.tes3cmd.ModOptions
import org.alpha3.launcher.tools.tes3cmd.Tes3Cmd
import org.alpha3.launcher.tools.tes3cmd.cleanPlugin
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.utils.currentTimeMillis

/** Where the downloader extracts mods, and where it notes the files that pruning deleted. */
val MODS_ROOT get() = "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Mods"
val PRUNE_MANIFEST get() = "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/alpha3_pruned.txt"

/** The OpenMW tools that can be run on the user's load order. */
enum class ModTool(val title: String, val description: String) {
    CLEAN(
        "Clean plugins (tes3cmd)",
        "Removes junk the Construction Set leaves in plugins: records and cell objects duplicated from the masters, evil GMSTs, junk cells. Originals are kept as <name>~1.esp."
    ),
    MERGE(
        "Merge records",
        "Combines records that several plugins change (leveled lists, NPC and creature inventories, spells and AI, containers, armour and more) so every mod's additions take effect. Writes alpha3_merged.omwaddon."
    ),
    GROUNDCOVER(
        "Groundcover",
        "Turns the grass, ferns and kelp placed by your plugins into OpenMW groundcover. Writes alpha3_groundcover.omwaddon and alpha3_deleted_groundcover.omwaddon."
    ),
    LIGHT_FIXES(
        "Light fixes",
        "Rebalances the colour, radius and flicker of every light in the load order for OpenMW's lighting. Writes alpha3_lightfixes.omwaddon."
    ),
}

/**
 * Runs the Kotlin ports of the OpenMW tools against the user's openmw.cfg. Generated plugins go to
 * [OpenMWPaths.USER_GENERATED], which the tools add to the cfg along with the plugins.
 */
class ModToolsRunner(private val fs: ToolFs, private val platform: ToolPlatform) {
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    // Replaced as a whole, never edited in place: tools log from background threads while the list is being measured
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    /** Keep the cleaned plugin in place of the original (the original is backed up) rather than as Clean_<name>. */
    var cleanOverwrite by mutableStateOf(true)

    /** Which record types the merge includes; a type that is switched off is passed to the merge as skipped. */
    val mergeTypes = mutableStateMapOf<String, Boolean>().also { m -> MERGEABLE_TYPES.forEach { m[it.first] = it.first !in DEFAULT_SKIPPED_TYPES } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val cfg get() = OpenMWPaths.USER_OPENMW_CFG
    private val outputDir get() = OpenMWPaths.USER_GENERATED

    // Tools log from worker threads; append through a CAS loop so lines are never lost (synchronized is JVM-only).
    @OptIn(ExperimentalAtomicApi::class)
    private val logLines = AtomicReference(emptyList<String>())

    @OptIn(ExperimentalAtomicApi::class)
    private fun log(msg: String) {
        val lines = msg.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        while (true) {
            val cur = logLines.load()
            val next = (cur + lines).takeLast(2000)
            if (logLines.compareAndSet(cur, next)) { log = next; return }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun clearLog() { logLines.store(emptyList()); log = emptyList() }

    fun start(tools: List<ModTool>) {
        if (running || tools.isEmpty()) return
        running = true
        job = scope.launch {
            try { run(tools) } finally { running = false }
        }
    }

    fun cancel() { job?.cancel() }

    /** What the last scan for overridden mod files found; null before a scan and after a delete. */
    var pruneReport by mutableStateOf<PruneReport?>(null)
        private set

    /** Experimental: lists the mod files that a later mod's file of the same path replaces. Changes nothing. */
    fun scanOverridden() {
        if (running) return
        running = true
        pruneReport = null
        job = scope.launch {
            try {
                status = "Scanning for overridden files"
                log("--- Overridden mod files")
                val r = findOverriddenFiles(fs, platform, cfg, MODS_ROOT, ::log)
                log("Scanned ${r.scanned} files: ${r.files.size} are overridden (${r.bytes / (1024 * 1024)} MB)")
                pruneReport = r
                status = "Scan done"
            } catch (e: Exception) {
                log("Scan failed: ${(e as? ToolException)?.message ?: e}")
                status = "Scan failed"
            } finally {
                running = false
            }
        }
    }

    /** Deletes the files the last scan found. Cannot be undone except by downloading the mods again. */
    fun deleteOverridden() {
        val report = pruneReport ?: return
        if (running) return
        running = true
        job = scope.launch {
            try {
                status = "Deleting overridden files"
                val n = deleteOverriddenFiles(fs, report, MODS_ROOT, PRUNE_MANIFEST, ::log)
                log("Freed ${report.bytes / (1024 * 1024)} MB in $n files")
                pruneReport = null
                status = "Done"
            } catch (e: Exception) {
                log("Delete failed: ${(e as? ToolException)?.message ?: e}")
                status = "Delete failed"
            } finally {
                running = false
            }
        }
    }

    /** Experimental: builds navmesh.db with the Kotlin port of openmw-navmeshtool. */
    fun generateNavMesh() {
        if (running) return
        running = true
        UIStateManager.navmeshBusy = true
        job = scope.launch {
            try {
                status = "Generating navmesh"
                log("--- Navmesh (Kotlin)")
                KotlinNavMesh.run(fs, platform, cfg, "${OpenMWPaths.USER_DATA}/navmesh.db", "${OpenMWPaths.ACTIVE_CONFIG}/navmeshtool.log", log = ::log)
                status = "Navmesh done"
            } catch (e: CancellationException) {
                status = "Cancelled"
                throw e
            } catch (e: Exception) {
                log("Navmesh failed: ${(e as? ToolException)?.message ?: e}")
                status = "Navmesh failed"
            } finally {
                UIStateManager.navmeshBusy = false
                running = false
            }
        }
    }

    private fun run(tools: List<ModTool>) {
        if (!fs.isFile(cfg)) {
            status = "No openmw.cfg yet"
            log("$cfg does not exist; set up the game files from the launcher first")
            return
        }
        fs.mkdirs(outputDir)
        val failures = ArrayList<String>()
        for (tool in ModTool.entries.filter { it in tools }) {
            status = "Running ${tool.title}"
            log("--- ${tool.title}")
            try {
                when (tool) {
                    ModTool.CLEAN -> clean()
                    ModTool.MERGE -> mergeRecords()
                    ModTool.GROUNDCOVER -> groundcover()
                    ModTool.LIGHT_FIXES -> lightFixes()
                }
            } catch (e: ToolException) {
                failures.add(tool.title)
                log("${tool.title} failed: ${e.message}")
            } catch (e: Exception) {
                failures.add(tool.title)
                log("${tool.title} failed unexpectedly: $e")
            }
        }
        try {
            val check = disableMissingConfigEntries(fs, platform, cfg, ::log)
            if (check.disabled.isNotEmpty()) log("Disabled ${check.disabled.size} openmw.cfg entries whose files are missing")
        } catch (e: Exception) {
            log("openmw.cfg check failed: ${e.message}")
        }
        status = if (failures.isEmpty()) "Done" else "Finished; failed: ${failures.joinToString(", ")}"
    }

    private fun clean() {
        val omw = readOpenMW(cfg, platform, fs)
        val vfs = Vfs(omw.data, emptyList(), TextCodec.forName(omw.encoding), fs)
        val tes3cmd = Tes3Cmd(fs, ::log, ::log, omw.data.asReversed())
        val options = CleanOptions(mod = ModOptions(overwrite = cleanOverwrite)).resolved(all = true)
        var cleaned = 0
        try {
            for (name in omw.content.distinct()) {
                // Only Morrowind-format plugins that live in a mod folder; the base game and generated plugins are left alone.
                if (!Regex("\\.es[pm]$", RegexOption.IGNORE_CASE).containsMatchIn(name) || name.startsWith("Clean_", ignoreCase = true)) continue
                val path = try { vfs.loose(name).path } catch (e: ToolNotFound) { continue }
                val file = File(path)
                if (!cleanOverwrite && File(file.parent, "Clean_${file.name}").exists()) continue
                tes3cmd.cleanPlugin(path, options)
                cleaned++
            }
        } finally {
            tes3cmd.close()
        }
        log("Looked at $cleaned plugins")
    }

    private fun mergeRecords() {
        val r = runAlpha3Merge(
            Alpha3MergeOptions(
                platform, fs, cfg, outputDir,
                skipTypes = MERGEABLE_TYPES.map { it.first }.filter { mergeTypes[it] == false }.toSet(),
                log = ::log,
            ),
            ::currentTimeMillis,
        )
        log("Merged ${r.records.size} records from ${r.plugins} plugins -> ${r.outputPath}")
    }

    private fun groundcover() {
        val toml = File(File(cfg).parent, "groundcoverify.toml")
        val config = if (toml.isFile) CoverifyConfig.fromToml(toml.readText()) else CoverifyConfig.DEFAULT
        val r = runCoverify(CoverifyOptions(platform, fs, cfg, outputDir, config, log = ::log), ::currentTimeMillis)
        log("Groundcover: ${r.references} placements in ${r.cells} cells, ${r.meshes.size} meshes")
    }

    private fun lightFixes() {
        val r = runLightFixes(LightFixesOptions(platform, fs, cfg, outputDir, writeLog = true, log = ::log))
        log("Light fixes: ${r.lights} lights, ${r.cells} cells, ${r.changes.size} changed")
    }
}
