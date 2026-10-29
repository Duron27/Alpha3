// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/** What [disableMissingConfigEntries] did to openmw.cfg. */
class ConfigCheck(
    /** The lines that were commented out, as they were written. */
    val disabled: List<String>,
    /** The copy of the original openmw.cfg, if the file was changed. */
    val backup: String?,
)

private val checkedKeys = setOf("data", "content", "groundcover", "fallback-archive")

/**
 * Comments out (with a leading `;`) every `data=`, `content=`, `groundcover=` and `fallback-archive=` line
 * of [cfgPath] whose folder or file does not exist, so OpenMW does not stop on a mod that failed to
 * download or a plugin that was never produced. Plugins and archives are looked for in the data folders
 * of the config (including the files it chains to); lines that are already comments are left alone, and
 * the original is first copied to `<name>.alpha3-check.bak`.
 *
 * If none of the data folders can be read the plugin and archive lines are kept, since that points at
 * storage that cannot be read rather than at missing files.
 */
fun disableMissingConfigEntries(fs: ToolFs, platform: ToolPlatform, cfgPath: String, log: (String) -> Unit = {}): ConfigCheck {
    var file = cfgPath
    if (fs.isDirectory(file)) file = joinPath(file, "openmw.cfg")
    file = cleanPath(file)
    val text = fs.readText(file)
    val effective = readOpenMW(file, platform, fs)

    val dataDirs = effective.data.filter { fs.isDirectory(it) }
    val names = HashSet<String>()
    for (dir in dataDirs) fs.list(dir)?.forEach { names.add(it.lowercase()) }
    val filesReadable = dataDirs.isNotEmpty()
    if (!filesReadable) log("No data folder could be read; keeping the plugin and archive lines")

    val disabled = ArrayList<String>()
    val out = text.split('\n').map { line ->
        val s = line.trim()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith(";") || s.startsWith("[")) return@map line
        val eq = s.indexOf('=')
        if (eq < 0) return@map line
        val key = s.substring(0, eq).trim()
        if (key !in checkedKeys) return@map line
        val missing = try {
            val value = cfgValue(s.substring(eq + 1))
            when (key) {
                "data" -> !fs.isDirectory(configPath(value, dirName(file), platform, fs))
                else -> filesReadable && value.lowercase() !in names
            }
        } catch (e: Exception) {
            false // a value this checker cannot read is not proof that something is missing
        }
        if (!missing) return@map line
        log("Disabled missing entry: $s")
        disabled.add(s)
        val indent = line.length - line.trimStart().length
        line.substring(0, indent) + ";" + line.substring(indent)
    }
    if (disabled.isEmpty()) return ConfigCheck(emptyList(), null)

    val backup = joinPath(dirName(file), "${baseName(file)}.alpha3-check.bak")
    writeText(fs, backup, text)
    val tmp = joinPath(dirName(file), ".${baseName(file)}.alpha3-check.tmp")
    writeText(fs, tmp, out.joinToString("\n"))
    fs.delete(file)
    fs.rename(tmp, file)
    return ConfigCheck(disabled, backup)
}

private fun writeText(fs: ToolFs, path: String, text: String) {
    fs.delete(path)
    val w = fs.openWrite(path)
    try {
        val b = text.encodeToByteArray()
        w.write(b, 0, b.size)
    } finally {
        w.close()
    }
}
