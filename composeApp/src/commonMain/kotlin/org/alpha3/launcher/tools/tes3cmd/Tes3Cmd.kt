// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.tes3cmd

import org.alpha3.launcher.tools.openmw.*

/** Options shared by commands that rewrite plugins. */
class ModOptions(
    /** Where backups go when [hideBackups] is set. */
    val backupDir: String? = null,
    val hideBackups: Boolean = false,
    /** Write results here instead of next to the input. */
    val outputDir: String? = null,
    /** Replace the original (keeping a numbered backup) instead of writing a `Clean_` copy. */
    val overwrite: Boolean = false,
)

/**
 * The tes3cmd command set over a [ToolFs]. Output goes to [out] (what tes3cmd prints) and [err]
 * (its warnings and errors). Masters are found next to the plugin first, then in [masterDirs].
 */
class Tes3Cmd(
    internal val fs: ToolFs,
    val out: (String) -> Unit = {},
    val err: (String) -> Unit = {},
    internal val masterDirs: List<String> = emptyList(),
    internal val verbose: Boolean = false,
) {
    internal var currentPlugin = ""

    private var masters: MasterIndex? = null

    /** Master files read so far; kept until [close], so a run reads each master once. */
    internal val masterIndex: MasterIndex get() = masters ?: MasterIndex(this).also { masters = it }

    /** Releases the master files. Call when done with this object. */
    fun close() {
        masters?.close()
        masters = null
    }

    internal fun error(msg: String) = err("[ERROR ($currentPlugin): $msg]\n")
    internal fun message(msg: String) = err("$msg\n")
    internal fun print(msg: String) = out("$msg\n")
    internal fun debug(msg: String) { if (verbose) err("[DEBUG ($currentPlugin): $msg]\n") }

    /** The path of master [name], looked up without regard to case. */
    internal fun findMaster(name: String, pluginDir: String): String? {
        for (dir in listOf(pluginDir) + masterDirs) {
            val hit = fs.list(dir)?.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
            return joinPath(dir, hit)
        }
        return null
    }

    internal fun copyFile(from: String, to: String) {
        val r = fs.openRead(from)
        try {
            fs.delete(to)
            val w = fs.openWrite(to)
            try {
                val buf = ByteArray(1 shl 16)
                var off = 0L
                while (true) {
                    val n = r.read(off, buf, 0, buf.size)
                    if (n <= 0) break
                    w.write(buf, 0, n)
                    off += n
                }
            } finally { w.close() }
        } finally { r.close() }
    }

    private val extRe = Regex("\\.(es[mps])$", RegexOption.IGNORE_CASE)

    /** `plugin~1.esp`, `plugin~2.esp`, ... the first name not taken. */
    internal fun uniqueBackupName(plugin: String, o: ModOptions): String {
        var bak = plugin
        if (o.hideBackups) {
            val dir = o.backupDir ?: fail("no backup directory set")
            fs.mkdirs(dir)
            bak = joinPath(dir, baseName(plugin))
        }
        val ext = extRe.find(bak)?.groupValues?.get(1) ?: return bak
        val numbered = Regex("(?:~(\\d+))?\\.$ext$")
        while (fs.isFile(bak)) bak = numbered.replace(bak) { "~${(it.groupValues[1].toIntOrNull() ?: 0) + 1}.$ext" }
        return bak
    }

    internal fun move(from: String, to: String) {
        fs.delete(to)
        fs.rename(from, to)
    }

    /**
     * Installs the temporary output of an update: as [newName] (the original stays), or over [plugin]
     * after saving a numbered backup. The record count in the new header is brought up to date.
     */
    internal fun fixOutput(plugin: String, modified: Boolean, newName0: String?, o: ModOptions) {
        val tmp = "$plugin.tmp"
        if (!modified) {
            print("$plugin was not modified")
            fs.delete(tmp)
            return
        }
        var newName = newName0
        if (o.outputDir != null) {
            fs.mkdirs(o.outputDir)
            newName = joinPath(o.outputDir, baseName(newName ?: plugin))
        }
        if (!extRe.containsMatchIn(plugin)) fail("\"$plugin\" name does not end in .esm/.esp/.ess")
        val mtime = fs.lastModified(plugin)
        if (newName != null && cleanPath(newName) == cleanPath(plugin)) {
            message("fix_output(): newname ($newName) same as plugin ($plugin)")
            newName = null
        }
        if (newName != null) {
            if (!extRe.containsMatchIn(newName)) fail("\"$newName\" name does not end in .esm/.esp/.ess")
            move(tmp, newName)
            updateHeader(newName, o, HeaderOptions(updateRecordCount = true), quiet = true, noBackup = true)
            fs.setLastModified(newName, mtime)
            out("Output saved in: \"$newName\"\nOriginal unaltered: \"$plugin\"\n")
        } else {
            val bak = uniqueBackupName(plugin, o)
            move(plugin, bak)
            move(tmp, plugin)
            updateHeader(plugin, o, HeaderOptions(updateRecordCount = true), quiet = true, noBackup = true)
            fs.setLastModified(plugin, mtime)
            out("Output saved in: \"$plugin\"\nOriginal backed up to: \"$bak\"\n")
        }
    }

    /**
     * Runs [fn] over every record of [plugin], writing the result to `<plugin>.tmp`. [fn] returns
     * [Action.Keep] to pass the record through, [Action.Drop] to delete it, or [Action.Replace].
     */
    internal fun processForUpdate(plugin: String, o: ModOptions, prefix: String?, fn: (Rec) -> Action): Boolean {
        if (!extRe.containsMatchIn(plugin)) fail("\"$plugin\" does not end in .esm/.esp/.ess")
        if (!fs.isFile(plugin)) fail("Invalid input file")
        val tmp = "$plugin.tmp"
        var modified = 0
        val reader = RecReader(fs.openRead(plugin), fs.size(plugin), baseName(plugin))
        fs.delete(tmp)
        val writer = OutFile(fs.openWrite(tmp))
        try {
            var expected: String? = "TES3"
            while (true) {
                val rec = reader.next(expected, warn = { message("new_from_input(): $it") }) ?: break
                expected = null
                when (val a = fn(rec)) {
                    Action.Keep -> writer.write(rec)
                    Action.Drop -> {
                        if (verbose) { if (modified == 0) print("UPDATED PLUGIN: $plugin"); print("RECORD DELETED: ${rec.tag} ${rec.id()}") }
                        modified++
                    }
                    is Action.Replace -> {
                        writer.write(a.rec)
                        if (verbose) { if (modified == 0) print("UPDATED PLUGIN: $plugin"); print("RECORD MODIFIED: ${rec.tag} ${rec.id()}") }
                        modified++
                    }
                }
            }
        } catch (e: ToolException) {
            message(e.message ?: "error")
            runCatching { writer.close() }
            reader.close()
            fs.delete(tmp)
            return false
        }
        writer.close()
        reader.close()
        val newName = prefix?.let { p ->
            val dir = dirName(plugin).takeIf { it != "." || plugin.contains('/') || plugin.contains('\\') }
            if (dir == null) "$p${baseName(plugin)}" else joinPath(dir, "$p${baseName(plugin)}")
        }
        fixOutput(plugin, modified > 0, newName, o)
        return true
    }
}

internal sealed class Action {
    object Keep : Action()
    object Drop : Action()
    class Replace(val rec: Rec) : Action()
}

/** Calls [fn] with the type and id of every record in [path] (the TES3 header excluded). */
fun collectIds(fs: ToolFs, path: String, fn: (type: String, id: String) -> Unit) {
    fs.eachRecord(path) { r ->
        if (r.tag != "TES3" && r.flags and HDR_IGNORED == 0L) fn(r.tag, r.id())
        true
    }
}
