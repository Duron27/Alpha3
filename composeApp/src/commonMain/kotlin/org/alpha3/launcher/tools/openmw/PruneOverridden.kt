// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.openmw

/** A mod file that another data folder later in the load order replaces. */
class OverriddenFile(val path: String, val size: Long, val overriddenBy: String)

class PruneReport(val files: List<OverriddenFile>, val scanned: Int) {
    val bytes: Long get() = files.sumOf { it.size }
}

/** The top-level folders of a data directory that OpenMW's VFS merges by path; everything else is left alone. */
private val vfsRoots = setOf(
    "meshes", "textures", "icons", "bookart", "splash", "sound", "music", "video",
    "fonts", "shaders", "mygui", "scripts", "l10n",
)

/**
 * Finds files below [modsRoot] that never reach the game because a data folder later in [cfgPath]'s load
 * order has a file with the same path (compared without regard to case), which is the one OpenMW uses.
 * Only files in the folders the VFS merges are considered, so plugins, archives and readmes stay put,
 * and files outside [modsRoot] (the Morrowind install, the generated plugins) are never reported.
 * Nothing is changed; pass the result to [deleteOverriddenFiles].
 */
fun findOverriddenFiles(fs: ToolFs, platform: ToolPlatform, cfgPath: String, modsRoot: String, log: (String) -> Unit = {}): PruneReport {
    val omw = readOpenMW(cfgPath, platform, fs)
    val all = omw.data.map { cleanPath(it) }
    // A folder listed twice counts at its last position, where it wins.
    val dirs = all.filterIndexed { i, d -> all.lastIndexOf(d) == i }
    val root = cleanPath(modsRoot).trimEnd('/') + "/"

    val winners = HashMap<String, String>()
    val losers = ArrayList<OverriddenFile>()
    var scanned = 0
    // Highest priority first: the first folder to have a path keeps it, later finds are the overridden ones.
    for ((n, dir) in dirs.asReversed().withIndex()) {
        if (!fs.isDirectory(dir)) continue
        val inMods = "$dir/".startsWith(root)
        log("Scanning ${n + 1}/${dirs.size}: ${baseName(dir)}")
        for (top in fs.list(dir).orEmpty()) {
            if (top.lowercase() !in vfsRoots || !fs.isDirectory(joinPath(dir, top))) continue
            val stack = ArrayDeque<Pair<String, String>>()
            stack.add(joinPath(dir, top) to top)
            while (stack.isNotEmpty()) {
                val (path, rel) = stack.removeLast()
                for (name in fs.list(path).orEmpty()) {
                    val p = joinPath(path, name)
                    val r = "$rel/$name"
                    if (fs.isDirectory(p)) { stack.add(p to r); continue }
                    scanned++
                    val key = r.lowercase()
                    val winner = winners[key]
                    if (winner == null) winners[key] = dir
                    else if (inMods) losers.add(OverriddenFile(p, fs.size(p), winner))
                }
            }
        }
    }
    return PruneReport(losers, scanned)
}

/**
 * Deletes the files of [report] and records them in [manifest] so the downloader does not extract them
 * again. A file outside [modsRoot] is refused. Returns how many files were deleted.
 */
fun deleteOverriddenFiles(fs: ToolFs, report: PruneReport, modsRoot: String, manifest: String, log: (String) -> Unit = {}): Int {
    val root = cleanPath(modsRoot).trimEnd('/') + "/"
    val previous = PrunedFiles.load(fs, manifest).paths
    val deleted = ArrayList<String>()
    for (f in report.files) {
        val path = cleanPath(f.path)
        if (!path.startsWith(root)) throw ToolException("refusing to delete outside the mods folder: ${f.path}")
        if (!fs.isFile(path)) continue
        fs.delete(path)
        deleted.add(path)
    }
    val text = (previous + deleted).joinToString("\n", postfix = "\n")
    val tmp = joinPath(dirName(manifest), ".${baseName(manifest)}.tmp")
    fs.mkdirs(dirName(manifest))
    fs.delete(tmp)
    val w = fs.openWrite(tmp)
    try { val b = text.encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() }
    fs.delete(manifest)
    fs.rename(tmp, manifest)
    log("Deleted ${deleted.size} files")
    return deleted.size
}

/** The files [deleteOverriddenFiles] removed, so a later extraction can treat them as already in place. */
class PrunedFiles private constructor(internal val paths: List<String>) {
    private val keys = paths.mapTo(HashSet()) { it.lowercase() }

    operator fun contains(path: String): Boolean = cleanPath(path).lowercase() in keys

    companion object {
        fun load(fs: ToolFs, manifest: String): PrunedFiles =
            PrunedFiles(if (fs.isFile(manifest)) fs.readText(manifest).lines().filter { it.isNotBlank() } else emptyList())
    }
}
