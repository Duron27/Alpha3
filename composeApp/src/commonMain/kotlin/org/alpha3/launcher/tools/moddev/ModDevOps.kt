package org.alpha3.launcher.tools.moddev

import org.alpha3.launcher.tools.coverify.CoverifyOptions
import org.alpha3.launcher.tools.coverify.runCoverify
import org.alpha3.launcher.tools.merge.Alpha3MergeOptions
import org.alpha3.launcher.tools.merge.runAlpha3Merge
import org.alpha3.launcher.tools.lightfixes.LightFixesOptions
import org.alpha3.launcher.tools.lightfixes.formatLog
import org.alpha3.launcher.tools.lightfixes.runLightFixes
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.ToolNotFound
import org.alpha3.launcher.tools.openmw.Vfs
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.tools.pluginquery.MatchSpec
import org.alpha3.launcher.tools.pluginquery.PluginInput
import org.alpha3.launcher.tools.pluginquery.QueryMode
import org.alpha3.launcher.tools.pluginquery.QueryOptions
import org.alpha3.launcher.tools.pluginquery.pluginDeps
import org.alpha3.launcher.tools.pluginquery.pluginSize
import org.alpha3.launcher.tools.pluginquery.recordTag
import org.alpha3.launcher.tools.pluginquery.runQuery
import org.alpha3.launcher.tools.tes3cmd.CleanOptions
import org.alpha3.launcher.tools.tes3cmd.DiffOptions
import org.alpha3.launcher.tools.tes3cmd.DumpOptions
import org.alpha3.launcher.tools.tes3cmd.HeaderOptions
import org.alpha3.launcher.tools.tes3cmd.ModOptions
import org.alpha3.launcher.tools.tes3cmd.RecordSelection
import org.alpha3.launcher.tools.tes3cmd.Tes3Cmd
import org.alpha3.launcher.tools.tes3cmd.cleanPlugin
import org.alpha3.launcher.tools.tes3cmd.commonRecords
import org.alpha3.launcher.tools.tes3cmd.convertPlugin
import org.alpha3.launcher.tools.tes3cmd.deleteFromPlugin
import org.alpha3.launcher.tools.tes3cmd.diffPlugins
import org.alpha3.launcher.tools.tes3cmd.dumpPlugins
import org.alpha3.launcher.tools.tes3cmd.recoverPlugin
import org.alpha3.launcher.tools.tes3cmd.updateHeader
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.currentTimeMillis

/** What a file in a mod folder is, by extension. */
internal enum class FileKind(val label: String, val extensions: Set<String>) {
    MESH("Meshes", setOf("nif", "kf", "kfa")),
    TEXTURE("Textures", setOf("dds", "tga", "bmp", "png", "jpg", "jpeg")),
    PLUGIN("Plugins", setOf("esp", "esm", "omwaddon", "omwgame", "omwscripts")),
    SCRIPT("Lua and shaders", setOf("lua", "omwfx", "glsl", "vert", "frag", "yaml")),
    AUDIO("Audio and video", setOf("wav", "mp3", "ogg", "bik", "mp4")),
    ARCHIVE("Archives", setOf("bsa", "zip", "7z", "rar")),
    OTHER("Other files", emptySet());

    companion object {
        fun of(name: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }
    }
}

internal class ModFiles(val root: File, val byKind: Map<FileKind, List<String>>) {
    val total get() = byKind.values.sumOf { it.size }
}

internal class FileConflict(val path: String, val others: List<String>)

internal class FileConflictReport(val total: Int, val wins: List<FileConflict>, val loses: List<FileConflict>)

internal class RecordConflict(val type: String, val id: String, val before: List<String>, val after: List<String>)

internal class RecordConflictReport(val recordCount: Int, val overrides: List<RecordConflict>, val overridden: List<RecordConflict>, val byType: Map<String, Int>)

/** Everything the mod dev tools do to a modlist's openmw.cfg, a mod folder, or a plugin, as text a dialog can show. */
internal class ModDevOps(private val fs: ToolFs, private val platform: ToolPlatform) {

    fun cleanDirName(raw: String): File = File(raw.trim().removeSurrounding("\"").removeSurrounding("'"))

    fun dataDirs(cfg: File): List<File> =
        runCatching { readOpenMW(cfg.path, platform, fs).data.map { File(it) } }.getOrDefault(emptyList())

    /** The plugin file called [name] in the data folders of [cfg]; later folders win. */
    fun findPlugin(cfg: File, name: String): File? {
        for (d in dataDirs(cfg).asReversed()) {
            val hit = fs.list(d.path)?.firstOrNull { it.equals(name.trim(), ignoreCase = true) } ?: continue
            return File(d, hit)
        }
        return null
    }

    /** Name of a data folder as the mod list shows it: its last two path parts. */
    fun displayName(dir: String): String = dir.replace('\\', '/').trimEnd('/').split('/').takeLast(2).joinToString("/")

    /** Every file under [root], as paths relative to it with '/' separators. */
    private fun walk(root: String): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<String>()
        stack.add("")
        while (stack.isNotEmpty()) {
            val rel = stack.removeLast()
            val dir = if (rel.isEmpty()) root else "$root/$rel"
            for (name in fs.list(dir).orEmpty()) {
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                val child = "$root/$childRel"
                if (fs.isDirectory(child)) stack.add(childRel) else out.add(childRel)
            }
        }
        return out
    }

    /** The files inside a Morrowind BSA, grouped like a folder's, and their sizes; throws with a readable message. */
    fun listArchive(archive: File): Pair<Map<FileKind, List<String>>, Map<String, Long>> {
        val bsa = org.alpha3.launcher.tools.BsaTool.openForReading(archive.path)
        try {
            val sizes = bsa.list.associate { it.name.replace('\\', '/') to it.fileSize }
            val groups = sizes.keys.groupBy { FileKind.of(it) }
            return FileKind.entries.mapNotNull { k -> groups[k]?.let { k to it.sortedBy(String::lowercase) } }.toMap() to sizes
        } finally {
            bsa.close()
        }
    }

    /**
     * Writes the file [rel] of [archive] to the cache (archive-view/<archive name>/<rel>) and returns it, so the
     * viewers and the audio player, which read files, can open it. Textures a model uses are still found in the archives.
     */
    fun extractFromArchive(archive: File, rel: String): File {
        val bsa = org.alpha3.launcher.tools.BsaTool.openForReading(archive.path)
        try {
            val entry = bsa.list.firstOrNull { it.name.replace('\\', '/').equals(rel, ignoreCase = true) }
                ?: error("$rel is not in ${archive.name}")
            val out = "${archiveViewDir().path}/${archive.nameWithoutExtension}"
            return File(org.alpha3.launcher.tools.BsaTool.extractEntry(bsa, entry, out))
        } finally {
            bsa.close()
        }
    }

    /** Where [extractFromArchive] puts files; emptied by [clearArchiveView]. */
    fun archiveViewDir(): File = File("${org.alpha3.launcher.paths.OpenMWPaths.CACHE_DIR}/archive-view")

    fun isArchiveView(file: File): Boolean = file.path.startsWith(archiveViewDir().path + "/")

    /** Removes every file taken out of an archive for viewing. */
    fun clearArchiveView() {
        runCatching { archiveViewDir().deleteRecursively() }
    }

    fun listFiles(dir: File): ModFiles {
        val groups = HashMap<FileKind, MutableList<String>>()
        for (rel in walk(dir.path)) groups.getOrPut(FileKind.of(rel)) { ArrayList() }.add(rel)
        return ModFiles(dir, FileKind.entries.associateWith { (groups[it] ?: emptyList()).sortedBy(String::lowercase) }.filterValues { it.isNotEmpty() })
    }

    /** Files this folder shares with other data folders, and which folder wins (the later one in openmw.cfg). */
    fun fileConflicts(cfg: File, mod: File): FileConflictReport {
        val dirs = dataDirs(cfg)
        val self = dirs.indexOfFirst { it.path.trimEnd('/') == mod.path.trimEnd('/') }
        val mine = HashSet<String>()
        for (rel in walk(mod.path)) mine.add(rel.lowercase())
        val providers = HashMap<String, MutableList<Int>>()
        for ((i, d) in dirs.withIndex()) {
            if (i == self || !d.isDirectory) continue
            for (rel in walk(d.path)) {
                val key = rel.lowercase()
                if (key in mine) providers.getOrPut(key) { ArrayList() }.add(i)
            }
        }
        val wins = ArrayList<FileConflict>(); val loses = ArrayList<FileConflict>()
        for ((path, idx) in providers.entries.sortedBy { it.key }) {
            val names = idx.map { displayName(dirs[it].path) }
            if (self < 0 || idx.any { it > self }) loses.add(FileConflict(path, names)) else wins.add(FileConflict(path, names))
        }
        return FileConflictReport(mine.size, wins, loses)
    }

    /** Records this plugin defines that other plugins in the load order define too. */
    fun recordConflicts(cfg: File, pluginName: String, pluginPath: File): RecordConflictReport {
        val omw = readOpenMW(cfg.path, platform, fs)
        val vfs = Vfs(omw.data, emptyList(), TextCodec.forName(omw.encoding), fs)
        val order = omw.content.filter { Regex("\\.(esp|esm|omwaddon|omwgame)$", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val mine = HashMap<String, Pair<String, String>>() // key -> (type, id)
        org.alpha3.launcher.tools.tes3cmd.collectIds(fs, pluginPath.path) { type, id -> mine["$type\u0000$id"] = type to id }
        val self = order.indexOfFirst { it.equals(pluginName, ignoreCase = true) }
        val before = HashMap<String, MutableList<String>>(); val after = HashMap<String, MutableList<String>>()
        for ((i, name) in order.withIndex()) {
            if (i == self) continue
            val path = try { vfs.loose(name).path } catch (e: ToolNotFound) { continue }
            runCatching {
                org.alpha3.launcher.tools.tes3cmd.collectIds(fs, path) { type, id ->
                    val key = "$type\u0000$id"
                    if (key in mine) (if (self < 0 || i < self) before else after).getOrPut(key) { ArrayList() }.add(name)
                }
            }
        }
        val overrides = before.map { (k, v) -> mine.getValue(k).let { RecordConflict(it.first, it.second, v, after[k].orEmpty()) } }
        val overridden = after.filterKeys { it !in before }.map { (k, v) -> mine.getValue(k).let { RecordConflict(it.first, it.second, emptyList(), v) } }
        val byType = HashMap<String, Int>()
        for (c in overrides + overridden) byType[c.type] = (byType[c.type] ?: 0) + 1
        val sortedBy = compareBy<RecordConflict>({ it.type }, { it.id })
        return RecordConflictReport(mine.size, overrides.sortedWith(sortedBy), overridden.sortedWith(sortedBy), byType)
    }

    // ---- tool wrappers; each returns what the command line tool would have printed

    private fun cmd(masterDirs: List<File>, sink: StringBuilder, verbose: Boolean = false) =
        Tes3Cmd(fs, { sink.append(it) }, { sink.append(it) }, masterDirs.map { it.path }.asReversed(), verbose)

    private inline fun capture(masterDirs: List<File>, verbose: Boolean = false, body: (Tes3Cmd) -> Unit): String {
        val sb = StringBuilder()
        val c = cmd(masterDirs, sb, verbose)
        try { body(c) } catch (e: ToolException) { sb.append("Error: ${e.message}\n") } finally { c.close() }
        return sb.toString().ifBlank { "(no output)" }
    }

    fun showHeader(plugin: File, masterDirs: List<File>) = capture(masterDirs) {
        it.updateHeader(plugin.path, ModOptions(), HeaderOptions(multiline = true))
        val masters = pluginDeps(fs, plugin.path)
        it.out("Masters: ${masters.joinToString(", ").ifEmpty { "(none)" }}\nRecords: ${pluginSize(fs, plugin.path)}\n")
    }

    fun syncHeader(plugin: File, masterDirs: List<File>) = capture(masterDirs) {
        it.updateHeader(plugin.path, ModOptions(), HeaderOptions.synchronize())
    }

    fun clean(plugin: File, masterDirs: List<File>, overwrite: Boolean) = capture(masterDirs) {
        it.cleanPlugin(plugin.path, CleanOptions(mod = ModOptions(overwrite = overwrite)).resolved(all = true))
    }

    fun convert(plugin: File, masterDirs: List<File>): String = capture(masterDirs) {
        val toMaster = plugin.extension.equals("esp", ignoreCase = true)
        it.convertPlugin(plugin.path, toMaster, overwrite = false)
    }

    fun recover(plugin: File, masterDirs: List<File>) = capture(masterDirs) { it.recoverPlugin(plugin.path, ModOptions()) }

    fun list(plugin: File, masterDirs: List<File>, type: String, idRegex: String) = capture(masterDirs) {
        val sel = RecordSelection(types = listOfNotNull(type.takeIf { t -> t.isNotBlank() }), ids = listOfNotNull(idRegex.takeIf { r -> r.isNotBlank() }))
        it.dumpPlugins(listOf(plugin.path), DumpOptions(sel, list = true, noBanner = true))
    }

    fun delete(plugin: File, masterDirs: List<File>, type: String, idRegex: String) = capture(masterDirs) {
        if (type.isBlank() && idRegex.isBlank()) throw ToolException("choose a record type or id first")
        val sel = RecordSelection(types = listOfNotNull(type.takeIf { t -> t.isNotBlank() }), ids = listOfNotNull(idRegex.takeIf { r -> r.isNotBlank() }))
        it.deleteFromPlugin(plugin.path, sel, ModOptions())
    }

    fun diff(a: File, b: File, masterDirs: List<File>) = capture(masterDirs) { it.diffPlugins(a.path, b.path, DiffOptions()) }

    fun common(a: File, b: File, masterDirs: List<File>) = capture(masterDirs) { it.out("Records in common:\n"); it.commonRecords(a.path, b.path) }

    fun query(plugin: File, type: String, idRegex: String, cellrefRegex: String, mode: QueryMode): String = try {
        val tag = if (type.isBlank()) throw ToolException("choose a record type") else recordTag(type)
        val spec = MatchSpec(
            tag, idRegex.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
            cellrefRegex.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
        )
        runQuery(QueryOptions(platform, fs, null, PluginInput.Files(listOf(plugin.path)), listOf(spec), mode = mode)).ifBlank { "(no matching records)" }
    } catch (e: ToolException) { "Error: ${e.message}" }

    /** Query across the whole load order of [cfg]. */
    fun queryLoadOrder(cfg: File, type: String, idRegex: String, cellrefRegex: String, mode: QueryMode): String = try {
        val tag = if (type.isBlank()) throw ToolException("choose a record type") else recordTag(type)
        val spec = MatchSpec(
            tag, idRegex.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
            cellrefRegex.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
        )
        runQuery(QueryOptions(platform, fs, cfg.path, PluginInput.All, listOf(spec), mode = mode)).ifBlank { "(no matching records)" }
    } catch (e: ToolException) { "Error: ${e.message}" }

    // ---- whole load order previews (nothing is written)

    fun previewMerge(cfg: File): String = try {
        val r = runAlpha3Merge(Alpha3MergeOptions(platform, fs, cfg.path, cfg.parentFile!!.path, pretend = true, enableInConfig = false), ::currentTimeMillis)
        buildString {
            append("${r.records.size} records would be merged from ${r.plugins} plugins:\n")
            r.records.forEach { append("  ${it.type} ${it.id}  from ${it.sources.joinToString(", ")}\n") }
        }
    } catch (e: ToolException) { "Error: ${e.message}" }

    fun previewGroundcover(cfg: File): String = try {
        val r = runCoverify(CoverifyOptions(platform, fs, cfg.path, cfg.parentFile!!.path, pretend = true, enableInConfig = false), ::currentTimeMillis)
        buildString {
            append("${r.objects.size} objects, ${r.references} placements in ${r.cells} cells, ${r.meshes.size} meshes\n")
            r.objects.forEach { append("  ${it.tag} ${it.id}  ${it.model}\n") }
        }
    } catch (e: ToolException) { "Error: ${e.message}" }

    fun previewLightFixes(cfg: File): String = try {
        formatLog(runLightFixes(LightFixesOptions(platform, fs, cfg.path, cfg.parentFile!!.path, dryRun = true, enableInConfig = false)))
    } catch (e: ToolException) { "Error: ${e.message}" }
}
