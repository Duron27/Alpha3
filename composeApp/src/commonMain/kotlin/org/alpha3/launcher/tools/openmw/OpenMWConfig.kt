// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

class OpenMWConfig(
    val data: List<String>,
    val content: List<String>,
    val archives: List<String>,
    val encoding: String,
    /** The `data-local` directory (also last in [data]), where OpenMW expects user-generated plugins. */
    val dataLocal: String? = null,
    /** Plugins listed with `groundcover=`. */
    val groundcover: List<String> = emptyList(),
)

/** Default openmw.cfg for [p], or null where there is no standard location (iOS). */
fun defaultOpenMWConfig(p: ToolPlatform, fs: ToolFs): String? {
    p.env["OPENMW_CONFIG"]?.takeIf { it.isNotEmpty() }?.let {
        val path = expandHome(it, p.home)
        return if (fs.isDirectory(path)) joinPath(path, "openmw.cfg") else path
    }
    val sep = if (p.os == OsKind.Windows) ';' else ':'
    for (dir in p.env["OPENMW_CONFIG_DIR"].orEmpty().split(sep)) {
        if (dir.isEmpty()) continue
        val path = joinPath(expandHome(dir, p.home), "openmw.cfg")
        if (fs.isFile(path)) return path
    }
    return when (p.os) {
        OsKind.Android -> "/storage/emulated/0/Alpha3/config/openmw.cfg"
        OsKind.MacOs -> joinPath(p.home, "Library/Preferences/openmw/openmw.cfg")
        OsKind.Windows -> joinPath(p.documents ?: joinPath(p.home, "Documents"), "My Games/OpenMW/openmw.cfg")
        OsKind.Ios -> null
        OsKind.Linux -> joinPath(p.env["XDG_CONFIG_HOME"]?.takeIf { it.isNotEmpty() } ?: joinPath(p.home, ".config"), "openmw/openmw.cfg")
    }
}

/** The Skeleton Key's per-modlist openmw.cfg, e.g. `~/.config/the-skeleton-key/i-heart-vanilla/openmw.cfg`. */
fun skeletonKeyOpenMWConfig(p: ToolPlatform, modList: String): String? {
    val root = when (p.os) {
        OsKind.Linux -> joinPath(p.home, ".config/the-skeleton-key")
        OsKind.MacOs -> joinPath(p.home, "Library/Preferences/the-skeleton-key")
        OsKind.Windows -> joinPath(p.documents ?: joinPath(p.home, "Documents"), "My Games/The Skeleton Key")
        else -> return null
    }
    return joinPath(joinPath(root, modList), "openmw.cfg")
}

/** OpenMW uses '&' to escape '&' and '"' inside quoted values; backslashes are literal. */
internal fun cfgValue(raw: String): String {
    val s = raw.trim()
    if (!s.startsWith("\"")) return s
    val out = StringBuilder()
    var i = 1
    while (i < s.length) {
        when (val c = s[i]) {
            '&' -> if (i + 1 < s.length && (s[i + 1] == '&' || s[i + 1] == '"')) out.append(s[++i]) else out.append(c)
            '"' -> {
                if (s.substring(i + 1).isNotBlank()) fail("unexpected text after quoted value \"$s\"")
                return out.toString()
            }
            else -> out.append(c)
        }
        i++
    }
    fail("unterminated quoted value \"$s\"")
}

internal fun configPath(value: String, base: String, p: ToolPlatform, fs: ToolFs): String {
    var v = value
    val tokens = listOf(
        "?userconfig?" to { dirName(defaultOpenMWConfig(p, fs) ?: fail("no default OpenMW config directory on this platform")) },
        "?local?" to { base },
        "?global?" to { "/etc/openmw" },
    )
    for ((token, dir) in tokens) {
        if (v.startsWith(token)) {
            v = joinPath(dir(), v.substring(token.length).trimStart('/', '\\'))
            break
        }
    }
    if (v.startsWith("?")) fail("unsupported OpenMW path token in \"$v\"")
    v = expandHome(v, p.home)
    if (!isAbsolutePath(v)) v = joinPath(base, v)
    return cleanPath(v)
}

/** Reads [name] and its `config=` chain, applying `replace=` like OpenMW does. */
internal fun readOpenMW(name: String, p: ToolPlatform, fs: ToolFs): OpenMWConfig {
    val visited = HashSet<String>()
    val layers = ArrayList<Map<String, MutableList<String>>>()

    fun visit(path: String) {
        var file = path
        if (fs.isDirectory(file)) file = joinPath(file, "openmw.cfg")
        file = cleanPath(file)
        if (!visited.add(file)) return
        val text = try {
            fs.readText(file)
        } catch (e: Exception) {
            fail("OpenMW config: $file: ${e.message}")
        }
        val layer = HashMap<String, MutableList<String>>()
        text.lineSequence().forEachIndexed { idx, line ->
            val s = line.removePrefix("﻿").trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("[")) return@forEachIndexed
            val eq = s.indexOf('=')
            if (eq < 0) fail("$file:${idx + 1}: expected key=value")
            val key = s.substring(0, eq).trim()
            if (key !in setOf("data", "data-local", "content", "groundcover", "fallback-archive", "config", "replace", "encoding")) return@forEachIndexed
            try {
                var value = cfgValue(s.substring(eq + 1))
                if (key == "data" || key == "data-local" || key == "config") value = configPath(value, dirName(file), p, fs)
                layer.getOrPut(key) { ArrayList() }.add(value)
            } catch (e: ToolException) {
                fail("$file:${idx + 1}: ${e.message}")
            }
        }
        if (layer["replace"].orEmpty().contains("config") && layers.size > 1) {
            while (layers.size > 1) layers.removeAt(layers.size - 1)
        }
        layers.add(layer)
        // Later config entries are visited first, matching OpenMW's stack.
        for (c in layer["config"].orEmpty().asReversed()) visit(c)
    }
    visit(cleanPath(name))

    var data = ArrayList<String>()
    var content = ArrayList<String>()
    var archives = ArrayList<String>()
    var groundcover = ArrayList<String>()
    var encoding = "win1252"
    var local = ""
    for (l in layers) {
        for (key in l["replace"].orEmpty()) when (key) {
            "data" -> data = ArrayList()
            "content" -> content = ArrayList()
            "fallback-archive" -> archives = ArrayList()
            "groundcover" -> groundcover = ArrayList()
        }
        data.addAll(l["data"].orEmpty())
        content.addAll(l["content"].orEmpty())
        archives.addAll(l["fallback-archive"].orEmpty())
        groundcover.addAll(l["groundcover"].orEmpty())
        l["data-local"]?.lastOrNull()?.let { local = it }
        l["encoding"]?.lastOrNull()?.let { encoding = it }
    }
    if (local.isNotEmpty()) data.add(local)
    return OpenMWConfig(data, content, archives, encoding, local.ifEmpty { null }, groundcover)
}
