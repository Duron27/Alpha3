package org.alpha3.launcher.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.baseName
import org.alpha3.launcher.tools.openmw.cleanPath
import org.alpha3.launcher.tools.openmw.dirName
import org.alpha3.launcher.tools.openmw.isAbsolutePath
import org.alpha3.launcher.tools.openmw.joinPath
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.tools.tes3cmd.CleanOptions
import org.alpha3.launcher.tools.tes3cmd.ModOptions
import org.alpha3.launcher.tools.tes3cmd.RecordSelection
import org.alpha3.launcher.tools.tes3cmd.Tes3Cmd
import org.alpha3.launcher.tools.tes3cmd.cleanPlugin
import org.alpha3.launcher.tools.tes3cmd.deleteFromPlugin

/** One source of a `copy-rename` action: [name] is the new file name, or null to strip the action's prefix. */
@Serializable
data class CopyRenameFile(val src: String, val name: String? = null)

/**
 * A post-extraction step from modding-openmw.com's mod lists (`download_info[].actions`), the same
 * data UMO runs. Flat on purpose: the site's `action` field says which of the other fields apply, and an
 * action this app does not know yet must not stop a whole list from loading.
 *
 *  - `remove`: [path] or [paths]
 *  - `rename`: [src] -> [dst]
 *  - `copy`: [src] or [srcs] -> [dst] (a folder for [srcs]); [force] merges into an existing folder
 *  - `copy-rename`: [files] into the folder [dst], each renamed to its `name` or stripped of [stripPrefix]
 *  - `clean`: run tes3cmd on [path] with [arguments] (a list, or a JSON list in a string); `clean` when absent
 *
 * All paths are relative to the mods folder of the mod's category.
 */
@Serializable
data class ModAction(
    val action: String,
    val path: String? = null,
    val paths: List<String>? = null,
    val src: String? = null,
    val srcs: List<String>? = null,
    val dst: String? = null,
    val force: Boolean? = null,
    val files: List<CopyRenameFile>? = null,
    @SerialName("strip_prefix") val stripPrefix: String? = null,
    val arguments: JsonElement? = null,
)

/**
 * Runs [actions] in order below [baseDir] (the `<mods>/<category>/` folder the archive was extracted
 * into), stopping at the first one that fails, like UMO. [masterDirs] are searched for the masters of
 * plugins that `clean` actions process. Throws [ToolException] when an action cannot be carried out.
 *
 * Unlike UMO this can run twice on the same files: removing something already gone, or renaming
 * something already renamed, is skipped rather than an error.
 */
fun runModActions(
    fs: ToolFs,
    baseDir: String,
    actions: List<ModAction>,
    masterDirs: List<String> = emptyList(),
    log: (String) -> Unit = {},
) {
    if (actions.isEmpty()) return
    ModActionRunner(fs, baseDir, masterDirs, log).run(actions)
}

/** The data directories of [cfgPath], newest first, for finding masters; empty if it cannot be read. */
fun modActionMasterDirs(fs: ToolFs, platform: ToolPlatform, cfgPath: String): List<String> =
    try { readOpenMW(cfgPath, platform, fs).data.asReversed() } catch (e: Exception) { emptyList() }

private val tes3cmdBackupArguments = setOf("--hide-backups", "tes3cmdbck", "--backup-dir")

private class ModActionRunner(
    private val fs: ToolFs,
    baseDir: String,
    private val masterDirs: List<String>,
    private val log: (String) -> Unit,
) {
    private val base = cleanPath(baseDir).trimEnd('/')

    fun run(actions: List<ModAction>) {
        for (a in actions) {
            when (a.action) {
                "remove" -> remove(a)
                "rename" -> rename(a)
                "copy" -> copy(a)
                "copy-rename" -> copyRename(a)
                "clean" -> clean(a)
                else -> log("skipping unknown action ${a.action}")
            }
        }
    }

    /** A path from an action, resolved below the base folder; anything that climbs out of it is refused. */
    private fun resolve(rel: String): String {
        val full = cleanPath("$base/$rel")
        if (rel.isEmpty() || isAbsolutePath(rel) || !full.startsWith("$base/")) throw ToolException("unallowed path in actions: $rel")
        return full
    }

    private fun exists(path: String) = fs.isFile(path) || fs.isDirectory(path)

    private fun makeParent(path: String) {
        val dir = dirName(path)
        if (!fs.isDirectory(dir)) fs.mkdirs(dir)
    }

    private fun remove(a: ModAction) {
        val targets = a.paths ?: listOfNotNull(a.path)
        if (targets.isEmpty()) throw ToolException("remove: no path")
        for (p in targets) {
            val target = resolve(p)
            if (!exists(target)) { log("remove: $p is already gone"); continue }
            log("removing $p")
            fs.delete(target)
        }
    }

    private fun rename(a: ModAction) {
        val src = a.src ?: throw ToolException("rename: no src")
        val dst = a.dst ?: throw ToolException("rename: no dst")
        val from = resolve(src)
        val to = resolve(dst)
        if (!exists(from)) {
            if (exists(to)) { log("rename: $src is already renamed"); return }
            throw ToolException("rename: $src: no such file or folder")
        }
        makeParent(to)
        log("renaming $src to $dst")
        fs.rename(from, to)
    }

    private fun copy(a: ModAction) {
        val dst = a.dst?.takeIf { it.isNotEmpty() } ?: throw ToolException("a copy action needs a destination")
        if ((a.src == null) == (a.srcs == null)) throw ToolException("a copy action needs either src or srcs")
        val pairs = if (a.src != null) listOf(a.src to dst) else a.srcs!!.map { s ->
            if (s.isEmpty() || baseName(s).let { it.isEmpty() || it == ".." }) throw ToolException("each copy source needs a file or folder name")
            s to "${dst.trimEnd('/')}/${baseName(s)}"
        }
        for ((s, d) in pairs) copyOne(s, d, a.force == true)
    }

    private fun copyOne(src: String, dst: String, force: Boolean) {
        val from = resolve(src)
        val to = resolve(dst)
        // The source can be gone after a run that already copied it, e.g. when overridden files were pruned.
        if (!exists(from) && exists(to)) { log("copy: $src is already copied"); return }
        makeParent(to)
        log("copying $src to $dst")
        if (fs.isDirectory(from)) copyTree(from, to, force) else copyFile(from, to)
    }

    private fun copyTree(from: String, to: String, force: Boolean) {
        if (fs.isDirectory(to) && !force) throw ToolException("copy: $to already exists")
        if (!fs.isDirectory(to)) fs.mkdirs(to)
        for (name in fs.list(from).orEmpty()) {
            val f = joinPath(from, name)
            val t = joinPath(to, name)
            if (fs.isDirectory(f)) copyTree(f, t, force) else copyFile(f, t)
        }
    }

    private fun copyFile(from: String, to: String) {
        if (!fs.isFile(from)) throw ToolException("copy: ${from.removePrefix(base)}: no such file or folder")
        // Like shutil.copy: a folder as the destination receives the file under its own name.
        val target = if (fs.isDirectory(to)) joinPath(to, baseName(from)) else to
        fs.delete(target)
        val reader = fs.openRead(from)
        try {
            val writer = fs.openWrite(target)
            try {
                val buf = ByteArray(64 * 1024)
                var offset = 0L
                while (true) {
                    val n = reader.read(offset, buf, 0, buf.size)
                    if (n <= 0) break
                    writer.write(buf, 0, n)
                    offset += n
                }
            } finally {
                writer.close()
            }
        } finally {
            reader.close()
        }
    }

    private fun copyRename(a: ModAction) {
        val files = a.files?.takeIf { it.isNotEmpty() } ?: throw ToolException("copy-rename: no files")
        val dst = a.dst?.takeIf { it.isNotBlank() } ?: throw ToolException("copy-rename needs a destination folder")
        val prefix = a.stripPrefix
        if (prefix != null && (prefix.isEmpty() || prefix.any { it in "/\\:\u0000" })) throw ToolException("strip_prefix must be a filename prefix")

        // Work out every source and destination before touching anything, so a bad rule changes no files.
        val copies = ArrayList<Pair<String, String>>()
        for (f in files) {
            val sources = if (hasPattern(f)) {
                val dir = dirName(f.src)
                val pattern = baseName(f.src)
                fs.list(resolve(dir))?.filter { fs.isFile(joinPath(resolve(dir), it)) && globMatches(pattern, it) }?.sorted()
                    ?.map { "$dir/$it" }.orEmpty()
                    .ifEmpty { throw ToolException("no files match copy-rename source: ${f.src}") }
            } else listOf(f.src)
            for (s in sources) copies.add(s to "${dst.trimEnd('/')}/${destinationName(f, s, prefix)}")
        }
        for ((s, d) in copies) {
            // A new file name is exact, even if a folder already has that name.
            if (!fs.isDirectory(resolve(s)) && fs.isDirectory(resolve(d))) throw ToolException("copy-rename: $d is a folder")
            copyOne(s, d, a.force == true)
        }
    }

    private fun hasPattern(f: CopyRenameFile): Boolean {
        if (f.name != null) return false
        if (dirName(f.src).any { it in "*?[" }) throw ToolException("source patterns are only supported in filenames")
        return baseName(f.src).any { it in "*?[" }
    }

    private fun destinationName(f: CopyRenameFile, source: String, prefix: String?): String {
        val name = f.name ?: run {
            if (prefix == null) throw ToolException("each source needs a name or a prefix to strip")
            val n = baseName(source)
            if (!n.startsWith(prefix)) throw ToolException("source filename does not start with strip_prefix: $source")
            n.removePrefix(prefix)
        }
        if (name.isBlank() || name == "." || name == ".." || name.any { it in "/\\:\u0000" }) throw ToolException("name must be a filename, without a directory path")
        return name
    }

    private fun clean(a: ModAction) {
        val rel = a.path ?: throw ToolException("clean: no path")
        val plugin = resolve(rel)
        if (!fs.isFile(plugin)) throw ToolException("clean: $rel: no such file")

        val given = arguments(a.arguments).filter { it !in tes3cmdBackupArguments }
        val args = if (a.arguments == null || given.isEmpty()) listOf("clean") else given
        val command = args.first()
        val rest = args.drop(1)

        val clean = BooleanArray(5) // instances, cell-params, dups, gmsts, junk-cells
        var all = false
        var overwrite = false
        var outputDir: String? = null
        val ignore = ArrayList<String>()
        val types = ArrayList<String>(); val ids = ArrayList<String>(); val exact = ArrayList<String>(); val flags = ArrayList<String>()
        val match = ArrayList<String>(); val noMatch = ArrayList<String>()
        var interior = false; var exterior = false
        var instMatch: String? = null; var instNoMatch: String? = null; var subMatch: String? = null; var subNoMatch: String? = null
        var i = 0
        fun next(): String = rest.getOrNull(++i) ?: throw ToolException("tes3cmd $command: missing value for ${rest[i - 1]}")
        while (i < rest.size) {
            when (val o = rest[i]) {
                "--instances" -> clean[0] = true
                "--cell-params" -> clean[1] = true
                "--dups" -> clean[2] = true
                "--gmsts" -> clean[3] = true
                "--junk-cells" -> clean[4] = true
                "--all" -> all = true
                "--overwrite", "--replace" -> overwrite = true
                "--output-dir" -> outputDir = next()
                "--ignore-plugin" -> ignore.add(next().lowercase())
                "--type" -> types.add(next())
                "--id" -> ids.add(next())
                "--exact-id" -> exact.add(next())
                "--flag" -> flags.add(next())
                "--interior" -> interior = true
                "--exterior" -> exterior = true
                "--match" -> match.add(next())
                "--no-match", "-M" -> noMatch.add(next())
                "--instance-match" -> instMatch = next()
                "--instance-no-match" -> instNoMatch = next()
                "--sub-match" -> subMatch = next()
                "--sub-no-match" -> subNoMatch = next()
                "--debug", "-d", "--no-cache", "--active" -> {}
                else -> throw ToolException("tes3cmd $command: unsupported option $o")
            }
            i++
        }
        if (baseName(plugin).lowercase() in ignore) return

        log("tes3cmd ${args.joinToString(" ")} $rel")
        val out: (String) -> Unit = { text -> text.lines().filter { it.isNotBlank() }.forEach(log) }
        val tes3cmd = Tes3Cmd(fs, out, out, masterDirs)
        try {
            val mod = ModOptions(outputDir = outputDir, overwrite = overwrite)
            when (command) {
                "clean" -> tes3cmd.cleanPlugin(plugin, CleanOptions(clean[0], clean[1], clean[2], clean[3], clean[4], mod).resolved(all))
                "delete" -> tes3cmd.deleteFromPlugin(
                    plugin,
                    RecordSelection(types, ids, exact, flags, interior, exterior, match, noMatch, instMatch, instNoMatch, subMatch, subNoMatch),
                    mod,
                )
                else -> throw ToolException("unsupported tes3cmd command $command")
            }
        } finally {
            tes3cmd.close()
        }
    }

    /** The site sends arguments as a list, but some entries are a JSON list inside a string. */
    private fun arguments(value: JsonElement?): List<String> = when (value) {
        null -> emptyList()
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            try { Json.decodeFromString<List<String>>(it.replace("'", "\"")) } catch (e: Exception) { throw ToolException("clean: cannot read arguments: $it") }
        }.orEmpty()
        else -> throw ToolException("clean: cannot read arguments")
    }
}

/** fnmatch-style matching (`*`, `?`, `[seq]`, `[!seq]`), case sensitive. */
private fun globMatches(pattern: String, name: String): Boolean {
    val re = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '*' -> re.append(".*")
            c == '?' -> re.append('.')
            c == '[' -> {
                val end = pattern.indexOf(']', i + 2)
                if (end < 0) re.append("\\[") else {
                    var body = pattern.substring(i + 1, end)
                    val negate = body.startsWith("!")
                    if (negate) body = body.substring(1)
                    re.append(if (negate) "[^" else "[").append(body.replace("\\", "\\\\").replace("^", "\\^")).append(']')
                    i = end
                }
            }
            else -> re.append(Regex.escape(c.toString()))
        }
        i++
    }
    return Regex(re.toString(), RegexOption.DOT_MATCHES_ALL).matches(name)
}
