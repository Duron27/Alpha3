// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.coverify

import org.alpha3.launcher.tools.openmw.*

import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class Selection {
    val objects = ArrayList<GameObject>()
    val cells = ArrayList<Cell>()
    val masters = ArrayList<Plugin>()
    val masterIndex = HashMap<Int, Int>()
    val meshNames = ArrayList<String>()
    var referenceCount = 0
    var movedCount = 0
}

internal fun selectOutput(w: World, plugins: List<Plugin>): Selection {
    val s = Selection()
    val ids = w.objects.values.map { it.key.id }.toHashSet()
    val usedIds = HashSet<String>()
    val used = HashSet<Int>()
    val meshNames = HashSet<String>()
    for (c in w.cells.values) {
        c.refs.entries.removeAll { it.value.id !in ids }
        if (c.refs.isEmpty()) continue
        s.cells.add(c)
        s.referenceCount += c.refs.size
        for ((k, r) in c.refs) {
            usedIds.add(r.id)
            if (k.number > 0xffffff) fail("reference ${w.names[k.plugin]}:0x${k.number.toString(16)} cannot fit in a TES3 override; refusing to truncate its ID")
            used.add(k.plugin)
            if (k in c.moved) s.movedCount++
        }
    }
    // Only final, live exterior placements justify copying an object or mesh.
    for (o in w.objects.values) {
        if (o.key.id !in usedIds) continue
        s.objects.add(o)
        if (o.model.isNotEmpty()) {
            val model = try { safeRelative(o.model) } catch (e: ToolException) { fail("${o.key.id} model: ${e.message}") }
            // Preserve upstream's case-sensitive prefix behavior for compatibility.
            if (!model.startsWith("grass/")) meshNames.add(model.lowercase())
        }
    }
    s.objects.sortWith(compareBy({ it.key.tag }, { it.key.id }))
    s.cells.sortWith(compareBy({ it.key.x }, { it.key.y }))
    if (used.size > 255) fail("references originate in ${used.size} plugins; TES3 can encode at most 255 reference masters in one output")
    // Only origins of retained grass placements are masters, kept in load order.
    for (p in plugins) if (p.id in used) s.masters.add(p)
    s.masters.forEachIndexed { i, p -> s.masterIndex[p.id] = i + 1 }
    for (id in used) if (id !in s.masterIndex) fail("selected reference depends on missing or ignored master \"${w.names[id]}\"")
    s.meshNames.addAll(meshNames.sorted())
    return s
}

private fun header(s: Selection, deleted: Boolean, codec: TextCodec): ByteArray {
    val h = ByteArray(300)
    floatBytes(1.3f).copyInto(h)
    var desc = "Generated groundcover plugin from vanilla-style groundcover\nShould be accompanied by a regular content plugin deleting the groundcover\n"
    var n = s.objects.size + s.cells.size
    if (deleted) {
        desc = "Generated Deleted Groundcover\nto be accompanied by a generated groundcover plugin\n"
        n = s.cells.size + if (s.referenceCount > 0) 1 else 0
    }
    desc.encodeToByteArray().copyInto(h, 40, 0, minOf(desc.length, 256))
    ByteBuilder(4).appendU32(n.toLong()).toByteArray().copyInto(h, 296)
    val b = ByteBuilder().sub("HEDR", h)
    for (p in s.masters) {
        b.sub("MAST", codec.encode(p.name) + 0)
        b.sub("DATA", ByteBuilder(8).appendU64(p.size).toByteArray())
    }
    return b.toByteArray()
}

internal fun buildPlugin(s: Selection, deleted: Boolean, codec: TextCodec): ByteArray {
    val out = ByteBuilder(1 shl 16)
    out.record("TES3", 0, header(s, deleted, codec))
    if (deleted && s.referenceCount > 0) out.record("ACTI", 0, ByteBuilder().strSub("NAME", "<deleted>").toByteArray())
    if (!deleted) {
        for (o in s.objects) {
            val data = ByteBuilder(o.data.size + 16)
            val prefixed = !o.model.replace('\\', '/').startsWith("grass/")
            subs(o.data) { tag, b ->
                data.sub(tag, if (tag == "MODL" && prefixed) "grass\\".encodeToByteArray() + b else b)
            }
            out.record(o.key.tag, o.flags, data.toByteArray())
        }
    }
    val data = ByteBuilder()
    for (c in s.cells) {
        data.clear()
        for (m in c.meta) data.sub(m.tag, m.data)
        val keys = c.refs.keys.sortedWith(compareBy({ s.masterIndex.getValue(it.plugin) }, { it.number }))
        for (k in keys) {
            val n = (s.masterIndex.getValue(k.plugin).toLong() shl 24) or k.number
            c.moved[k]?.let { target ->
                data.uintSub("MVRF", n)
                data.sub("CNDT", target)
            }
            data.uintSub("FRMR", n)
            if (deleted) {
                data.strSub("NAME", "<deleted>")
                data.uintSub("DELE", 0)
            } else {
                val r = c.refs.getValue(k).data
                data.append(r)
            }
        }
        out.record("CELL", 0, data.toByteArray())
    }
    return out.toByteArray()
}

internal class MeshJob(val name: String, val source: Source)

@Serializable
private class OutputManifest(val version: Int, val meshes: List<String>)

private val manifestJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun previousMeshes(root: String, fs: ToolFs): List<String> {
    val path = joinPath(root, MANIFEST_NAME)
    if (!fs.isFile(path)) return emptyList()
    val m = try { Json.decodeFromString<OutputManifest>(fs.readText(path)) } catch (e: Exception) { fail("output manifest: ${e.message}") }
    if (m.version != 1) fail("unsupported output manifest version ${m.version}")
    val seen = HashSet<String>()
    return m.meshes.map { name ->
        val n = try { safeRelative(name) } catch (e: ToolException) { fail("invalid mesh in output manifest: \"$name\"") }
        if (!n.startsWith("meshes/grass/") || !seen.add(n)) fail("invalid mesh in output manifest: \"$name\"")
        n
    }
}

/** Creates parent directories and rejects destinations that are not plain files. */
private fun prepareDestination(root: String, name: String, fs: ToolFs) {
    var p = root
    val parts = name.split('/')
    for ((i, part) in parts.withIndex()) {
        p = joinPath(p, part)
        val last = i == parts.size - 1
        if (fs.isDirectory(p)) {
            if (last) fail("unexpected output file type: $p")
        } else if (fs.isFile(p)) {
            if (!last) fail("unexpected output file type: $p")
        } else if (!last) {
            fs.mkdirs(p)
        }
    }
}

/**
 * Stages every artifact before replacing any output. Each replaced file keeps a backup until the
 * whole commit succeeds, so ordinary errors can be rolled back.
 */
internal fun publish(c: CoverifyConfig, outDir: String, s: Selection, jobs: List<MeshJob>, vfs: Vfs, codec: TextCodec, fs: ToolFs) {
    val previous = previousMeshes(outDir, fs)
    fs.mkdirs(outDir)
    var stage: String
    do { stage = joinPath(outDir, ".gocoverify-" + Random.nextLong().toULong().toString(16)) } while (fs.isDirectory(stage) || fs.isFile(stage))
    fs.mkdirs(stage)
    var clean = true
    try {
        val names = ArrayList<String>()
        for (j in jobs) names.add("meshes/grass/${j.name}")
        val manifest = OutputManifest(1, names.toList())
        val stale = previous.filter { it !in names }
        for ((i, j) in jobs.withIndex()) vfs.copy(j.source, joinPath(stage, names[i]), fs)
        for ((i, name) in listOf(c.groundcoverOutput, c.deletedOutput).withIndex()) {
            val w = fs.openWrite(joinPath(stage, name))
            try {
                val bytes = buildPlugin(s, i == 1, codec)
                w.write(bytes, 0, bytes.size)
            } finally {
                w.close()
            }
            names.add(name)
        }
        val mw = fs.openWrite(joinPath(stage, MANIFEST_NAME))
        try {
            val bytes = (manifestJson.encodeToString(OutputManifest.serializer(), manifest) + "\n").encodeToByteArray()
            mw.write(bytes, 0, bytes.size)
        } finally {
            mw.close()
        }
        names.add(MANIFEST_NAME)
        val installedCount = names.size
        names.addAll(stale)
        for (name in names) prepareDestination(outDir, name, fs)
        val backupDir = joinPath(stage, "backups")
        fs.mkdirs(backupDir)

        class Change(val dest: String, var backup: String? = null, var installed: Boolean = false)
        val changes = ArrayList<Change>()
        try {
            for ((i, name) in names.withIndex()) {
                val dest = joinPath(outDir, name)
                val x = Change(dest)
                if (fs.isFile(dest)) {
                    x.backup = joinPath(backupDir, i.toString())
                    fs.rename(dest, x.backup!!)
                }
                changes.add(x)
                if (i >= installedCount) continue
                fs.rename(joinPath(stage, name), dest)
                x.installed = true
            }
        } catch (e: Exception) {
            for (x in changes.asReversed()) {
                try {
                    if (x.installed) fs.delete(x.dest)
                    x.backup?.let { fs.rename(it, x.dest) }
                } catch (re: Exception) {
                    clean = false
                    fail("${e.message}; rollback failed: ${re.message}; backups retained in $stage")
                }
            }
            throw e
        }
    } finally {
        if (clean) fs.delete(stage)
    }
}
