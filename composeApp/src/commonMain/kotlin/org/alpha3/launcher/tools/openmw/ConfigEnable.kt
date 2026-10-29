// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/** What [enableInOpenMWConfig] did to openmw.cfg. */
class ConfigEdit(
    /** Lines that were appended; empty if everything was already enabled. */
    val added: List<String>,
    /** The copy of the original openmw.cfg, if the file was changed. */
    val backup: String?,
) {
    val changed get() = added.isNotEmpty()
}

private fun quoted(path: String): String = "\"" + path.replace("&", "&&").replace("\"", "&\"") + "\""

private fun sameDir(a: String, b: String) = cleanPath(a).trimEnd('/') == cleanPath(b).trimEnd('/')

/**
 * Makes a tool's output usable by OpenMW: adds [dataDir] as a `data=` directory, and each of
 * [content] and [groundcover] as `content=` / `groundcover=` entries, unless openmw.cfg (including
 * the files it chains to) already has them. New lines go at the end of [cfgPath] so the plugins
 * load last, and the original is first copied to `<name>.<tool>.bak`.
 */
internal fun enableInOpenMWConfig(
    fs: ToolFs,
    cfgPath: String,
    omw: OpenMWConfig,
    tool: String,
    dataDir: String?,
    content: List<String> = emptyList(),
    groundcover: List<String> = emptyList(),
): ConfigEdit {
    if (dataDir != null && !isAbsolutePath(dataDir)) fail("cannot add the relative folder \"$dataDir\" to openmw.cfg; use an absolute path")
    var file = cfgPath
    if (fs.isDirectory(file)) file = joinPath(file, "openmw.cfg")
    val lines = ArrayList<String>()
    if (dataDir != null && omw.data.none { sameDir(it, dataDir) }) lines.add("data=${quoted(cleanPath(dataDir))}")
    for (g in groundcover) if (omw.groundcover.none { it.equals(g, ignoreCase = true) }) lines.add("groundcover=$g")
    for (c in content) if (omw.content.none { it.equals(c, ignoreCase = true) }) lines.add("content=$c")
    if (lines.isEmpty()) return ConfigEdit(emptyList(), null)

    val text = fs.readText(file)
    val backup = joinPath(dirName(file), "${baseName(file)}.$tool.bak")
    fs.delete(backup)
    fs.openWrite(backup).let { w -> try { val b = text.encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() } }
    val updated = text + (if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + lines.joinToString("\n", postfix = "\n")
    val tmp = joinPath(dirName(file), ".${baseName(file)}.$tool.tmp")
    fs.delete(tmp)
    fs.openWrite(tmp).let { w -> try { val b = updated.encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() } }
    fs.delete(file)
    fs.rename(tmp, file)
    return ConfigEdit(lines, backup)
}
