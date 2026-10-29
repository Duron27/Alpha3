// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.pluginquery

import org.alpha3.launcher.tools.openmw.*

/** Where records come from: every plugin in openmw.cfg, or these plugin files in this order. */
sealed class PluginInput {
    object All : PluginInput()
    class Files(val paths: List<String>) : PluginInput()
}

class QueryOptions(
    val platform: ToolPlatform,
    val fs: ToolFs,
    /** Needed for [PluginInput.All]; with [PluginInput.Files] it only supplies the text encoding. */
    val openMWConfig: String? = null,
    val input: PluginInput,
    val matches: List<MatchSpec>,
    val ignoredPlugins: List<String> = emptyList(),
    val mode: QueryMode = QueryMode.Dump,
    /** Report each plugin's own records instead of the merged load order. */
    val individual: Boolean = false,
    val log: (String) -> Unit = {},
)

enum class QueryMode { Dump, Ids, Count }

private class Session(val codec: TextCodec, val vfs: Vfs, val plugins: List<PluginFile>, val omw: OpenMWConfig?)

private fun openSession(platform: ToolPlatform, fs: ToolFs, cfg: String?, input: PluginInput, ignored: Set<String>, log: (String) -> Unit, requireConfig: Boolean = false): Session {
    val needCfg = requireConfig || input is PluginInput.All || cfg != null
    val path = cfg?.takeIf { it.isNotEmpty() } ?: if (needCfg) defaultOpenMWConfig(platform, fs) else null
    val omw = path?.let { readOpenMW(it, platform, fs) }
    val codec = TextCodec.forName(omw?.encoding ?: "win1252")
    val vfs = Vfs(omw?.data.orEmpty(), omw?.archives.orEmpty(), codec, fs)
    val plugins = ArrayList<PluginFile>()
    when (input) {
        is PluginInput.All -> {
            val seen = HashSet<String>()
            for (name in omw?.content ?: fail("no OpenMW config; supply the path explicitly")) {
                val key = name.lowercase()
                if (key in ignored || !seen.add(key)) continue
                when (key.substringAfterLast('.', "")) {
                    "esp", "esm", "omwaddon", "omwgame" -> {}
                    "omwscripts" -> continue
                    else -> { log("Warning: unrecognized plugin type for $name"); continue }
                }
                val s = try { vfs.loose(name) } catch (e: ToolNotFound) { fail("content plugin $name: ${e.message}") }
                plugins.add(PluginFile(name, s.path, s.size))
            }
        }
        is PluginInput.Files -> for (p in input.paths) {
            if (!fs.isFile(p)) fail("$p: no such file")
            plugins.add(PluginFile(baseName(p), p, fs.size(p)))
        }
    }
    return Session(codec, vfs, plugins, omw)
}

private fun loadInto(s: Session, merger: Merger, p: PluginFile, tags: Set<String>, fs: ToolFs, log: (String) -> Unit): Boolean {
    var masters = emptyList<String>()
    return try {
        scanPlugin(p, tags, s.codec, fs, onHeader = { masters = it.masters }) { tag, flags, body ->
            merger.apply(p.name, masters, tag, flags, body)
        }
        true
    } catch (e: ToolException) {
        log("Error: skipping plugin ${p.name}: ${e.message}")
        false
    }
}

/** Dumps the records matching [QueryOptions.matches], like `delta_plugin query`, as raw subrecords. */
fun runQuery(o: QueryOptions): String {
    val ignored = o.ignoredPlugins.map { it.lowercase() }.toSet()
    val s = openSession(o.platform, o.fs, o.openMWConfig, o.input, ignored, o.log)
    val tags = o.matches.map { it.tag }.toSet()
    val out = StringBuilder()
    fun render(merger: Merger) {
        val sel = merger.select(o.matches)
        when (o.mode) {
            QueryMode.Count -> out.append(sel.size).append('\n')
            QueryMode.Ids -> sel.map { it.merged.id.display }.sorted().forEach { out.append(it).append('\n') }
            QueryMode.Dump -> sel.forEach { dumpRecord(out, it, s.codec) }
        }
    }
    if (o.individual) {
        for (p in s.plugins) {
            val m = Merger(tags, s.codec)
            if (!loadInto(s, m, p, tags, o.fs, o.log) || m.select(o.matches).isEmpty()) continue
            out.append("Plugin ${p.path}:\n")
            render(m)
        }
    } else {
        val m = Merger(tags, s.codec)
        for (p in s.plugins) loadInto(s, m, p, tags, o.fs, o.log)
        render(m)
    }
    return out.toString()
}

class FilterOptions(
    val platform: ToolPlatform,
    val fs: ToolFs,
    val openMWConfig: String? = null,
    val input: PluginInput,
    val matches: List<MatchSpec>,
    /** Output plugin path; it is ignored as an input. */
    val output: String,
    val author: String = "The Skeleton Key",
    val description: String = "",
    val ignoredPlugins: List<String> = emptyList(),
    val log: (String) -> Unit = {},
)

class FilterResult(val outputPath: String, val records: Int, val cells: Int, val references: Int)

/** Writes the matching records of the merged load order to a new plugin, like `delta_plugin filter`. */
fun runFilter(o: FilterOptions): FilterResult {
    val ignored = (o.ignoredPlugins + baseName(o.output)).map { it.lowercase() }.toSet()
    val s = openSession(o.platform, o.fs, o.openMWConfig, o.input, ignored, o.log)
    val tags = o.matches.map { it.tag }.toSet()
    val merger = Merger(tags, s.codec)
    val loaded = s.plugins.filter { loadInto(s, merger, it, tags, o.fs, o.log) }
    val selected = merger.select(o.matches)
    val bytes = writeFilterPlugin(selected, loaded, o, s.codec)
    val parent = dirName(o.output)
    o.fs.mkdirs(parent)
    o.fs.delete(o.output)
    val w = o.fs.openWrite(o.output)
    try { w.write(bytes.plugin, 0, bytes.plugin.size) } finally { w.close() }
    return FilterResult(o.output, selected.size, bytes.cells, bytes.references)
}

/** Masters of [path], one per line in `delta_plugin deps`. */
fun pluginDeps(fs: ToolFs, path: String, encoding: String = "win1252"): List<String> {
    val masters = ArrayList<String>()
    scanPlugin(PluginFile(baseName(path), path, fs.size(path)), emptySet(), TextCodec.forName(encoding), fs, onHeader = { masters.addAll(it.masters) }) { _, _, _ -> }
    return masters
}

/** Number of records in a plugin, not counting its header. */
fun pluginSize(fs: ToolFs, path: String, encoding: String = "win1252"): Int =
    scanPlugin(PluginFile(baseName(path), path, fs.size(path)), emptySet(), TextCodec.forName(encoding), fs) { _, _, _ -> }

/** Absolute path of [file] in the OpenMW VFS; for archive files, the archive path followed by the stored name. */
fun vfsFind(platform: ToolPlatform, fs: ToolFs, openMWConfig: String?, file: String): String {
    val s = openSession(platform, fs, openMWConfig, PluginInput.Files(emptyList()), emptySet(), {}, requireConfig = true)
    val src = try { s.vfs.resolve(file) } catch (e: ToolNotFound) { fail("entity not found") }
    return if (src.archive) "${src.path}/${src.entry}" else src.path
}

fun vfsExtract(platform: ToolPlatform, fs: ToolFs, openMWConfig: String?, file: String, output: String) {
    val s = openSession(platform, fs, openMWConfig, PluginInput.Files(emptyList()), emptySet(), {}, requireConfig = true)
    val src = try { s.vfs.resolve(file) } catch (e: ToolNotFound) { fail("entity not found") }
    fs.delete(output)
    s.vfs.copy(src, output, fs)
}
