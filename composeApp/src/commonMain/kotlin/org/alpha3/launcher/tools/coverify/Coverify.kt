// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.coverify

import org.alpha3.launcher.tools.merge.parallelMap
import org.alpha3.launcher.tools.openmw.*
import org.alpha3.launcher.utils.availableCpuCores

const val COVERIFY_VERSION = "0.1.0"

class CoverifyOptions(
    val platform: ToolPlatform,
    val fs: ToolFs,
    /** The openmw.cfg whose `config=` chain supplies the load order; null uses the platform default. */
    val openMWConfig: String? = null,
    /** Where to write the plugins and `meshes/`; overrides the TOML's output_directory. */
    val outputDirectory: String? = null,
    val config: CoverifyConfig = CoverifyConfig.DEFAULT,
    val ignoredPlugins: List<String> = emptyList(),
    /** Resolve and list matches without writing anything. */
    val pretend: Boolean = false,
    /** Add the output folder and plugins to openmw.cfg if they are not there yet. */
    val enableInConfig: Boolean = true,
    val log: (String) -> Unit = {},
)

class CoverifyMatch(val tag: String, val id: String, val model: String)
class CoverifyMesh(val name: String, val sourcePath: String, val inArchive: Boolean, val archiveOffset: Long)

class CoverifyResult(
    val openMWConfig: String,
    val outputDirectory: String,
    val plugins: Int,
    val objects: List<CoverifyMatch>,
    val meshes: List<CoverifyMesh>,
    val cells: Int,
    val references: Int,
    val movedReferences: Int,
    val seconds: Double,
    val pretend: Boolean,
    /** Lines added to openmw.cfg, if any. */
    val configLinesAdded: List<String> = emptyList(),
)

/**
 * Converts ordinary exterior objects in an OpenMW load order into groundcover: a groundcover plugin,
 * a plugin deleting the originals, and the matching meshes under `meshes/grass/`.
 * Run after your merge tool; the OpenMW config is left unchanged.
 */
fun runCoverify(o: CoverifyOptions, nowMillis: () -> Long = { 0L }): CoverifyResult {
    val start = nowMillis()
    val fs = o.fs
    val c = o.config.also { it.validate() }
    val outDir = o.outputDirectory ?: c.outputDirectory
    val cfgPath = o.openMWConfig?.takeIf { it.isNotEmpty() }
        ?: c.openMWConfig.takeIf { it.isNotEmpty() }
        ?: defaultOpenMWConfig(o.platform, fs)
        ?: fail("no default OpenMW config on this platform; supply the path explicitly")

    val omw = readOpenMW(cfgPath, o.platform, fs)
    val codec = TextCodec.forName(omw.encoding)
    val matcher = Matcher(c)
    val vfs = Vfs(omw.data, omw.archives, codec, fs)
    val world = World(matcher, codec, fs)

    val exclude = (o.ignoredPlugins + c.ignoredPlugins + c.deletedOutput + c.groundcoverOutput).map { it.lowercase() }.toHashSet()
    val plugins = ArrayList<Plugin>()
    val seen = HashSet<String>()
    for (name in omw.content) {
        val key = name.lowercase()
        if (key in exclude || !seen.add(key)) continue
        when (key.substringAfterLast('.', "")) {
            "esp", "esm", "omwaddon", "omwgame" -> {}
            "omwscripts" -> continue
            else -> fail("unsupported content file \"$name\"")
        }
        val s = try { vfs.loose(name) } catch (e: Exception) { fail("content plugin $name: ${e.message}") }
        plugins.add(Plugin(baseName(s.path), s.path, s.size, world.pluginId(name)))
    }
    if (plugins.isEmpty()) fail("no content plugins in $cfgPath")

    o.log("Reading ${plugins.size} plugins from $cfgPath")
    // Plugins are read on one thread per core; what they change is applied in load order, so the last plugin still wins
    val changes = parallelMap(plugins, availableCpuCores(), { n -> if (n % 50 == 0) o.log("  read $n of ${plugins.size} plugins") }) { world.parse(it) }
    for ((i, p) in plugins.withIndex()) world.apply(p, changes[i])
    o.log("Read ${plugins.size} plugins")
    val s = selectOutput(world, plugins)
    o.log("Matched ${s.objects.size} objects, ${s.referenceCount} exterior references in ${s.cells.size} cells; resolving ${s.meshNames.size} meshes")
    val jobs = s.meshNames.map { name ->
        try { MeshJob(name, vfs.resolve("meshes/$name")) } catch (e: Exception) { fail("mesh $name: ${e.message}") }
    }
    var added = emptyList<String>()
    if (!o.pretend) {
        publish(c, outDir, s, jobs, vfs, codec, fs)
        o.log("Wrote ${joinPath(outDir, c.groundcoverOutput)} and ${joinPath(outDir, c.deletedOutput)}")
        if (o.enableInConfig) {
            val edit = enableInOpenMWConfig(fs, cfgPath, omw, "alpha3-groundcover", outDir, content = listOf(c.deletedOutput), groundcover = listOf(c.groundcoverOutput))
            if (edit.changed) o.log("Added ${edit.added.joinToString(", ")} to $cfgPath (backup: ${edit.backup})")
            added = edit.added
        }
    }
    val seconds = (nowMillis() - start) / 1000.0
    o.log("Done: ${s.referenceCount} placements, ${s.movedCount} moved references, ${jobs.size} meshes")
    return CoverifyResult(
        cfgPath, outDir, plugins.size,
        s.objects.map { CoverifyMatch(it.key.tag, it.key.id, it.model) },
        jobs.map { CoverifyMesh(it.name, it.source.path, it.source.archive, it.source.offset) },
        s.cells.size, s.referenceCount, s.movedCount, seconds, o.pretend, added,
    )
}
