// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

import org.alpha3.launcher.tools.openmw.*
import org.alpha3.launcher.utils.availableCpuCores

const val DEFAULT_MERGE_OUTPUT = "alpha3_merged.omwaddon"

/**
 * Every record type the merge can handle, as the name `skipTypes` takes (a four-letter tag, "Dialogue" for topics,
 * "DialogueInfo" for their infos, or "Cell") and a label. Cells are never merged, so skipping them changes nothing;
 * they are listed because DeltaPlugin has the option.
 */
val MERGEABLE_TYPES: List<Pair<String, String>> = listOf(
    "Cell" to "Cells (never merged by this tool)",
    "LEVI" to "Leveled item lists", "LEVC" to "Leveled creature lists", "Dialogue" to "Dialogue topics", "DialogueInfo" to "Dialogue infos",
    "NPC_" to "NPCs", "CREA" to "Creatures", "CONT" to "Containers", "ARMO" to "Armor", "CLOT" to "Clothing",
    "WEAP" to "Weapons", "ALCH" to "Potions", "ENCH" to "Enchantments", "SPEL" to "Spells", "BSGN" to "Birthsigns",
    "MGEF" to "Magic effects", "SKIL" to "Skills", "RACE" to "Races", "REGN" to "Regions", "FACT" to "Factions", "CLAS" to "Classes", "ACTI" to "Activators",
    "APPA" to "Apparatus", "BOOK" to "Books", "DOOR" to "Doors", "INGR" to "Ingredients", "LIGH" to "Lights",
    "LOCK" to "Lockpicks", "MISC" to "Misc items", "PROB" to "Probes", "REPA" to "Repair items",
    "SNDG" to "Sound generators", "SOUN" to "Sounds", "STAT" to "Statics", "BODY" to "Body parts",
)

/** What the merge skips unless asked otherwise, the same as `--skip Cell --skip Dialogue --skip DialogueInfo` on DeltaPlugin. */
val DEFAULT_SKIPPED_TYPES: Set<String> = setOf("Cell", "Dialogue", "DialogueInfo")

/** Output names of earlier versions; they are never merged again, and the old file is removed. */
private val legacyOutputs = listOf("alpha3_leveled_lists.omwaddon")

class Alpha3MergeOptions(
    val platform: ToolPlatform,
    val fs: ToolFs,
    /** The openmw.cfg whose `config=` chain supplies the load order; null uses the platform default. */
    val openMWConfig: String? = null,
    val outputDirectory: String,
    val outputName: String = DEFAULT_MERGE_OUTPUT,
    val ignoredPlugins: List<String> = emptyList(),
    /** Record types (four-letter tags such as NPC_ or CONT) to leave out of the merge. */
    val skipTypes: Set<String> = emptySet(),
    /** Merge and report without writing the plugin. */
    val pretend: Boolean = false,
    /** Add the output folder and plugin to openmw.cfg if they are not there yet. */
    val enableInConfig: Boolean = true,
    val log: (String) -> Unit = {},
)

class MergedRecordInfo(val type: String, val id: String, val sources: List<String>)

class Alpha3MergeResult(
    val openMWConfig: String,
    val outputPath: String,
    val plugins: Int,
    val records: List<MergedRecordInfo>,
    val seconds: Double,
    val pretend: Boolean,
    /** Lines added to openmw.cfg, if any. */
    val configLinesAdded: List<String> = emptyList(),
)

/**
 * Merges the records of every plugin in an OpenMW load order that can be merged (NPCs, creatures,
 * containers, levelled lists, items, spells and so on) into one plugin, so that mods that change
 * different parts of the same record all take effect. Put the result at the end of the load order.
 * The behaviour follows DeltaPlugin's `merge`.
 */
fun runAlpha3Merge(o: Alpha3MergeOptions, nowMillis: () -> Long = { 0L }): Alpha3MergeResult {
    val start = nowMillis()
    val fs = o.fs
    if (o.outputName.isEmpty() || o.outputName.any { it == '/' || it == '\\' }) fail("output plugin must be a filename, got \"${o.outputName}\"")
    val cfgPath = o.openMWConfig?.takeIf { it.isNotEmpty() }
        ?: defaultOpenMWConfig(o.platform, fs)
        ?: fail("no default OpenMW config on this platform; supply the path explicitly")

    val omw = readOpenMW(cfgPath, o.platform, fs)
    val codec = TextCodec.forName(omw.encoding)
    val vfs = Vfs(omw.data, omw.archives, codec, fs)

    // OMWLLF is the older merger for the same job; its output would be merged twice.
    val ignore = (o.ignoredPlugins + o.outputName + legacyOutputs + "OMWLLF.omwaddon").map { it.lowercase() }.toHashSet()
    val warn: (String) -> Unit = { o.log("Warning: $it") }

    // First pass: every plugin's header and the keys of its mergeable records.
    val scans = LinkedHashMap<String, PluginScan>()
    o.log("Reading the headers of ${omw.content.size} plugins")
    val scanStart = nowMillis()
    // Finding the files goes through the VFS cache, so it stays on this thread; reading them is spread over several.
    class ToScan(val key: String, val name: String, val path: String, val size: Long)
    val toScan = ArrayList<ToScan>()
    val seen = HashSet<String>()
    for (name in omw.content) {
        val key = name.lowercase()
        when (key.substringAfterLast('.', "")) {
            "esp", "esm", "omwaddon", "omwgame" -> {}
            "omwscripts" -> continue
            else -> { warn("unrecognized plugin type for $name"); continue }
        }
        if (!seen.add(key)) continue
        try {
            val s = vfs.loose(name)
            toScan.add(ToScan(key, name, s.path, s.size))
        } catch (e: ToolException) {
            warn("skipping unreadable plugin $name: ${e.message}")
        } catch (e: ToolNotFound) {
            warn("content plugin $name: ${e.message}")
        }
    }
    class Scanned(val item: ToScan, val scan: PluginScan?, val warning: String?)
    // Plugins are read on one thread per core
    val threads = availableCpuCores()
    val scanned = parallelMap(toScan, threads, { n -> if (n % 50 == 0) o.log("  read $n of ${toScan.size} plugins") }) { t ->
        try {
            Scanned(t, scanPlugin(t.name, t.path, t.size, codec, fs), null)
        } catch (e: ToolException) {
            Scanned(t, null, "skipping unreadable plugin ${t.name}: ${e.message}")
        }
    }
    for (r in scanned) {
        r.warning?.let { warn(it) }
        val scan = r.scan ?: continue
        if (scan.doNotMerge) o.log("Skipping ${r.item.name} (tagged DoNotMerge)") else scans[r.item.key] = scan
    }
    val order = scans.keys.filter { it !in ignore }
    if (order.isEmpty()) fail("no content plugins in $cfgPath")
    o.log("Read ${scans.size} plugins in ${(nowMillis() - scanStart) / 1000} s")

    // A record only one plugin defines is already what the game uses; only shared records need merging.
    val counts = HashMap<String, Int>()
    for (scan in scans.values) for (k in scan.keys.toHashSet()) counts[k] = (counts[k] ?: 0) + 1
    // DeltaPlugin's `--skip` takes record type names; "Dialogue" is the topics and "DialogueInfo" their infos.
    val skipped = o.skipTypes.map {
        when (it.lowercase()) { "dialogue" -> "DIAL"; "dialogueinfo" -> "INFO"; "cell" -> "CELL"; else -> it.uppercase() }
    }.toHashSet()
    val wanted = counts.filter { (k, n) -> n > 1 && k.substringBefore("::").uppercase() !in skipped }.keys

    o.log("Merging records from ${order.size} plugins in $cfgPath")
    o.log("${counts.size} records in all, ${wanted.size} of them in more than one plugin and not skipped" +
        if (skipped.isEmpty()) "" else " (skipping ${skipped.sorted().joinToString(", ")})")
    val loaded = LinkedHashMap<String, MergePlugin>()
    val loadStart = nowMillis()
    class Loaded(val key: String, val plugin: MergePlugin?, val warnings: List<String>)
    val loads = parallelMap(scans.entries.toList(), threads, { n -> if (n % 50 == 0) o.log("  loaded $n of ${scans.size} plugins") }) { (key, scan) ->
        val warnings = ArrayList<String>()
        try {
            Loaded(key, loadMergePlugin(scan, wanted, codec, fs) { warnings.add(it) }, warnings)
        } catch (e: ToolException) {
            warnings.add("skipping unreadable plugin ${scan.name}: ${e.message}")
            Loaded(key, null, warnings)
        }
    }
    for (l in loads) {
        l.warnings.forEach { warn(it) }
        l.plugin?.let { loaded[l.key] = it }
    }
    o.log("Loaded ${loaded.size} plugins in ${(nowMillis() - loadStart) / 1000} s")
    val mergeStart = nowMillis()
    val merged = mergeRecords(loaded, order.filter { it in loaded }, o.log)
        .sortedWith(compareBy({ it.value.recordTag }, { it.value.recordId.lowercase() }))
    o.log("Merged in ${(nowMillis() - mergeStart) / 1000} s: " +
        merged.groupingBy { it.value.recordTag }.eachCount().entries.sortedBy { it.key }.joinToString(", ") { "${it.value} ${it.key}" })

    val outPath = joinPath(o.outputDirectory, o.outputName)
    var added = emptyList<String>()
    if (!o.pretend) {
        fs.mkdirs(o.outputDirectory)
        val bytes = buildPlugin(merged.map { it.value }, order.mapNotNull { loaded[it] }, codec)
        val tmp = joinPath(o.outputDirectory, ".${o.outputName}.tmp")
        fs.delete(tmp)
        val w = fs.openWrite(tmp)
        try { w.write(bytes, 0, bytes.size) } finally { w.close() }
        fs.delete(outPath)
        fs.rename(tmp, outPath)
        o.log("Wrote $outPath")
        for (old in legacyOutputs) joinPath(o.outputDirectory, old).let { if (fs.isFile(it)) { fs.delete(it); o.log("Removed the old $old") } }
        if (o.enableInConfig) {
            val edit = enableInOpenMWConfig(fs, cfgPath, omw, "alpha3-merge", o.outputDirectory, content = listOf(o.outputName))
            if (edit.changed) o.log("Added ${edit.added.joinToString(", ")} to $cfgPath (backup: ${edit.backup})")
            added = edit.added
        }
    }
    o.log("Done: ${merged.size} merged records")
    o.log("Shared records by type: " + wanted.groupingBy { it.substringBefore("::") }.eachCount().entries.sortedBy { it.key }.joinToString(", ") { "${it.value} ${it.key}" })
    o.log("Loaded records by type: " + loaded.values.flatMap { p -> p.records.values.filterNotNull().map { it.recordTag } }
        .groupingBy { it }.eachCount().entries.sortedBy { it.key }.joinToString(", ") { "${it.value} ${it.key}" })
    return Alpha3MergeResult(
        cfgPath, outPath, order.size,
        merged.map { MergedRecordInfo(it.value.recordTag, it.value.recordId, it.sources) },
        (nowMillis() - start) / 1000.0, o.pretend, added,
    )
}

private fun buildPlugin(records: List<Mergeable>, masters: List<MergePlugin>, codec: TextCodec): ByteArray {
    class Out(val tag: String, val flags: Long, val body: ByteArray)

    fun fieldOut(r: FieldRecord): Out {
        val body = ByteBuilder()
        body.sub(
            r.schema.idTag,
            if (r.schema.idIsIndex) ByteBuilder(4).appendU32(r.recordId.toLong()).toByteArray() else codec.encode(r.recordId) + 0,
        )
        for (s in r.toSubs()) body.sub(s.tag, s.data)
        return Out(r.recordTag, r.flags, body.toByteArray())
    }

    val out = ArrayList<Out>()
    for (r in records) {
        when {
            r is LeveledList -> out.add(Out(r.kind.tag, 0, leveledListBody(r, codec)))
            r is FieldRecord && r.recordTag != "DIAL" && r.recordTag != "INFO" -> out.add(fieldOut(r))
        }
    }
    // Dialogue: each topic's header, then the infos that changed. OpenMW merges infos by id, so the rest come from the plugins.
    val topics = LinkedHashMap<String, FieldRecord?>()
    for (r in records) if (r is FieldRecord && r.recordTag == "DIAL") topics[r.recordId.lowercase()] = r
    for (r in records) if (r is FieldRecord && r.recordTag == "INFO") topics.getOrPut(r.parent!!.lowercase()) { null }
    for ((topic, merged) in topics) {
        val header = merged
            ?: masters.asReversed().firstNotNullOfOrNull { it.records[mergeKey("DIAL", topic)] as? FieldRecord }
            ?: continue
        out.add(fieldOut(header))
        for (r in records) if (r is FieldRecord && r.recordTag == "INFO" && r.parent!!.lowercase() == topic) out.add(fieldOut(r))
    }

    val header = ByteArray(300)
    floatBytes(1.3f).copyInto(header)
    "Alpha3".encodeToByteArray().copyInto(header, 8)
    "Auto-generated merged records\nDoNotMerge".encodeToByteArray().copyInto(header, 40)
    ByteBuilder(4).appendU32(out.size.toLong()).toByteArray().copyInto(header, 296)
    val h = ByteBuilder().sub("HEDR", header)
    for (m in masters) {
        h.sub("MAST", codec.encode(m.name) + 0)
        h.sub("DATA", ByteBuilder(8).appendU64(m.size).toByteArray())
    }
    val file = ByteBuilder(1 shl 14)
    file.record("TES3", 0, h.toByteArray())
    for (o in out) file.record(o.tag, o.flags, o.body)
    return file.toByteArray()
}
