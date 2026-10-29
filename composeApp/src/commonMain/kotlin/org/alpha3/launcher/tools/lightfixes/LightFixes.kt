// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.lightfixes

import org.alpha3.launcher.tools.openmw.*

class LightFixesOptions(
    val platform: ToolPlatform,
    val fs: ToolFs,
    /** An openmw.cfg, or the directory holding it; null uses the platform default. */
    val openMWConfig: String? = null,
    /** Where to write alpha3_lightfixes.omwaddon; overrides the config. Must already exist. */
    val outputDirectory: String? = null,
    /** Settings to use instead of reading lightconfig.toml next to openmw.cfg. */
    val config: LightFixesConfig? = null,
    val args: LightFixesArgs = LightFixesArgs(),
    /** Work out and report the changes without writing anything. */
    val dryRun: Boolean = false,
    /** Also write alpha3_lightfixes.log next to openmw.cfg. */
    val writeLog: Boolean = false,
    /** Add the plugin (and its folder) to openmw.cfg if they are not there yet. */
    val enableInConfig: Boolean = true,
    val log: (String) -> Unit = {},
)

/** One record lightfixes changed, with the plugin it came from. */
class LightFixesChange(val type: String, val id: String, val plugin: String, val changes: List<String>)

class LightFixesResult(
    val openMWConfig: String,
    val configFile: String?,
    val outputPath: String,
    val contentFiles: Int,
    val loadedPlugins: Int,
    val masters: Int,
    val lights: Int,
    val cells: Int,
    val changes: List<LightFixesChange>,
    val enabledInConfig: Boolean,
    val dryRun: Boolean,
)

private class LightRecord(val id: String, val flags: Long, val subs: List<Sub>, val data: LightData)
private class CellRecord(val id: String, val flags: Long, val meta: List<Sub>)

private fun hex(c: IntArray) = c.take(3).toString()

/**
 * Rewrites the colour, radius, duration and flicker/pulse of every light in an OpenMW load order
 * (and optionally interior ambient lighting) into alpha3_lightfixes.omwaddon. Later plugins win, as
 * in the game. Load the result last.
 */
fun runLightFixes(o: LightFixesOptions): LightFixesResult {
    val fs = o.fs
    var cfgPath = (o.openMWConfig?.takeIf { it.isNotEmpty() } ?: defaultOpenMWConfig(o.platform, fs)
        ?: fail("no default OpenMW config on this platform; supply the path explicitly")).let { cleanPath(it) }
    if (fs.isDirectory(cfgPath)) cfgPath = joinPath(cfgPath, "openmw.cfg")
    if (!fs.isFile(cfgPath)) fail("OpenMW config $cfgPath does not exist")
    val userConfigDir = dirName(cfgPath)
    val omw = readOpenMW(cfgPath, o.platform, fs)

    // Settings: the file next to openmw.cfg (created from the defaults if missing), then the overrides.
    val configFile = fs.list(userConfigDir)?.firstOrNull { it.equals(LIGHT_CONFIG_NAME, ignoreCase = true) }?.let { joinPath(userConfigDir, it) }
    val config = o.config ?: configFile?.let { LightFixesConfig.fromToml(fs.readText(it)) } ?: LightFixesConfig()
    if (config.unknownKeys.isNotEmpty()) {
        o.log("Warning: $LIGHT_CONFIG_NAME has settings S3LightFixes does not know, so this run ignores them: ${config.unknownKeys.joinToString(", ") { "`$it`" }}")
    }
    o.args.applyTo(config)

    val outDir = o.outputDirectory?.also { if (!fs.isDirectory(it)) fail("The requested output path $it does not exist") }
        ?: config.outputDir?.takeIf { it.isNotEmpty() }
        ?: omw.dataLocal ?: "."
    val compiled = CompiledLightFixes(config)

    if (!o.dryRun && (configFile == null && o.config == null || config.saveConfig || o.args.updateConfig)) {
        val target = configFile ?: joinPath(userConfigDir, LIGHT_CONFIG_NAME)
        fs.delete(target)
        val w = fs.openWrite(target)
        try { val b = config.toToml().encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() }
        o.log("Wrote $target")
    }

    if (omw.content.isEmpty()) fail("No plugins were found in $cfgPath! No lights to fix!")
    val codec = TextCodec.forName(omw.encoding)
    val vfs = Vfs(omw.data, emptyList(), codec, fs)

    // Plugins that can be fixed, in load order.
    class Source(val path: String, val size: Long)
    val sources = ArrayList<Source>()
    for (name in omw.content) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("esp", "esm", "omwaddon", "omwgame")) continue
        if (name.equals(LIGHTFIXES_PLUGIN_NAME, ignoreCase = true) || name.substringBeforeLast('.').equals("S3LightFixes", ignoreCase = true)) continue
        val s = try { vfs.loose(name) } catch (e: ToolNotFound) { continue }
        if (compiled.isExcludedPlugin(baseName(s.path))) continue
        sources.add(Source(s.path, s.size))
    }

    // Newest plugin first, so the first definition of an id seen is the one in effect.
    val usedIds = HashSet<String>()
    val lights = ArrayList<Pair<LightRecord, Boolean>>()
    val cells = ArrayList<CellRecord>()
    val changes = ArrayList<LightFixesChange>()
    val masters = ArrayList<Pair<String, Long>>()
    var loaded = 0
    for (src in sources.asReversed()) {
        val pluginName = baseName(src.path)
        val pluginLights = ArrayList<LightRecord>()
        val pluginCells = ArrayList<Pair<CellRecord, ByteArray>>()
        try {
            scanPlugin(PluginFile(pluginName, src.path, src.size), setOf("LIGH", "CELL"), codec, fs) { tag, flags, body ->
                if (tag == "LIGH") {
                    val subs = readSubs(body)
                    if (subs.any { it.tag == "DELE" } || flags and 0x20L != 0L) return@scanPlugin
                    val id = subs.firstOrNull { it.tag == "NAME" }?.let { codec.decode(it.data) } ?: return@scanPlugin
                    val data = subs.firstOrNull { it.tag == "LHDT" }?.let { LightData.parse(it.data) } ?: return@scanPlugin
                    pluginLights.add(LightRecord(id, flags, subs, data))
                } else {
                    val cell = parseCell(body, pluginName, emptyList(), codec)
                    val data = cell.meta.firstOrNull { it.tag == "DATA" }?.data ?: return@scanPlugin
                    val ambi = cell.meta.firstOrNull { it.tag == "AMBI" }?.data
                    if (data.size != 12 || data[0].toInt() and 1 == 0 || ambi == null || ambi.size != 16) return@scanPlugin
                    val name = cell.meta.firstOrNull { it.tag == "NAME" }?.let { codec.decode(it.data) } ?: return@scanPlugin
                    pluginCells.add(CellRecord(name, flags, cell.meta) to ambi)
                }
            }
        } catch (e: ToolException) {
            o.log("[ WARNING ]: Plugin ${src.path}: could not be loaded due to error: ${e.message}. Continuing light fixes without this mod.")
            continue
        }
        loaded++
        var used = 0

        for ((cell, ambi) in pluginCells) {
            val cellId = cell.id.lowercase()
            if (cellId in usedIds || compiled.isExcludedId(cellId)) continue
            val atmo = ambi.copyOf()
            var replaced = false
            if (config.disableInteriorSun) { for (i in 4 until 8) atmo[i] = 0; replaced = true }
            for ((pattern, r) in compiled.ambientRegexes) {
                if (!pattern.containsMatchIn(cellId)) continue
                r.ambient?.let { c -> c.toEsp().forEachIndexed { i, v -> atmo[i] = v.toByte() }; replaced = true }
                r.fog?.let { c -> c.toEsp().forEachIndexed { i, v -> atmo[8 + i] = v.toByte() }; replaced = true }
                r.sunlight?.let { c -> c.toEsp().forEachIndexed { i, v -> atmo[4 + i] = v.toByte() }; replaced = true }
                r.fogDensity?.let { d -> d.toBits().let { b -> for (i in 0 until 4) atmo[12 + i] = (b ushr (8 * i)).toByte() }; replaced = true }
            }
            if (!replaced) continue
            val diff = ArrayList<String>()
            if (!ambi.copyOfRange(0, 4).contentEquals(atmo.copyOfRange(0, 4))) diff.add("ambient")
            if (!ambi.copyOfRange(4, 8).contentEquals(atmo.copyOfRange(4, 8))) diff.add("sunlight")
            if (!ambi.copyOfRange(8, 12).contentEquals(atmo.copyOfRange(8, 12))) diff.add("fog")
            if (!ambi.copyOfRange(12, 16).contentEquals(atmo.copyOfRange(12, 16))) diff.add("fog_density")
            if (diff.isNotEmpty()) changes.add(LightFixesChange("CELL", cellId, pluginName, diff))
            // Keep the cell's own data but drop references and the water height, as lightfixes does.
            val meta = cell.meta.filter { it.tag != "WHGT" && it.tag != "INTV" }.map { if (it.tag == "AMBI") Sub("AMBI", atmo) else it }
            cells.add(CellRecord(cell.id, cell.flags, meta))
            usedIds.add(cellId)
            used++
        }

        for (light in pluginLights) {
            val id = light.id.lowercase()
            if (id in usedIds || compiled.isExcludedId(id)) continue
            usedIds.add(id)
            val diff = processLight(compiled, id, light.data)
            if (diff.isNotEmpty()) changes.add(LightFixesChange("LIGH", light.id, pluginName, diff))
            lights.add(light to true)
            used++
        }
        if (used > 0) masters.add(0, pluginName to src.size)
    }

    val outPath = joinPath(outDir, LIGHTFIXES_PLUGIN_NAME)
    if (masters.isEmpty()) {
        if (o.dryRun) return result(o, cfgPath, configFile, outPath, omw, loaded, masters.size, 0, 0, changes, false, true)
        fail("The generated plugin was not found to have any master files! It's empty!")
    }
    if (o.dryRun) return result(o, cfgPath, configFile, outPath, omw, loaded, masters.size, lights.size, cells.size, changes, false, true)

    val bytes = buildPlugin(lights.map { it.first }, cells, masters, codec)
    // An older copy in data-local would shadow the new one.
    omw.dataLocal?.let { fs.delete(joinPath(it, LIGHTFIXES_PLUGIN_NAME)) }
    fs.mkdirs(outDir)
    fs.delete(outPath)
    val w = fs.openWrite(outPath)
    try { w.write(bytes, 0, bytes.size) } finally { w.close() }

    val enabled = o.enableInConfig || config.autoEnable
    if (enabled) {
        val edit = enableInOpenMWConfig(fs, cfgPath, omw, "alpha3-lightfixes", outDir, content = listOf(LIGHTFIXES_PLUGIN_NAME))
        if (edit.changed) o.log("Added ${edit.added.joinToString(", ")} to $cfgPath (backup: ${edit.backup})")
    }
    val res = result(o, cfgPath, configFile, outPath, omw, loaded, masters.size, lights.size, cells.size, changes, enabled, false)
    if (o.writeLog) {
        val logPath = joinPath(userConfigDir, LIGHT_LOG_NAME)
        fs.delete(logPath)
        val lw = fs.openWrite(logPath)
        try { val b = formatLog(res).encodeToByteArray(); lw.write(b, 0, b.size) } finally { lw.close() }
    }
    o.log("$LIGHTFIXES_PLUGIN_NAME generated${if (enabled) ", enabled," else ""} and saved in $outDir")
    return res
}

private fun result(o: LightFixesOptions, cfg: String, configFile: String?, out: String, omw: OpenMWConfig, loaded: Int, masters: Int, lights: Int, cells: Int, changes: List<LightFixesChange>, enabled: Boolean, dry: Boolean) =
    LightFixesResult(cfg, configFile, out, omw.content.size, loaded, masters, lights, cells, changes, enabled, dry)

/** The text written to alpha3_lightfixes.log and shown by a dry run. */
fun formatLog(r: LightFixesResult): String = buildString {
    if (r.dryRun) append("Dry run: no files written\n")
    append("# S3LightFixes (Kotlin port)\n# config: ${r.openMWConfig}\n# output: ${r.outputPath}\n")
    append("# content files: ${r.contentFiles}\n# loaded plugins: ${r.loadedPlugins}\n# masters: ${r.masters}\n")
    append("# changed cells: ${r.changes.count { it.type == "CELL" }}\n# changed lights: ${r.changes.count { it.type == "LIGH" }}\n")
    for (c in r.changes) append("${c.type} \"${c.id}\" from \"${c.plugin}\": ${c.changes.joinToString(", ")}\n")
}

private val optionalStrings = setOf("MODL", "FNAM", "ITEX", "SCRI", "SNAM")

private fun buildPlugin(lights: List<LightRecord>, cells: List<CellRecord>, masters: List<Pair<String, Long>>, codec: TextCodec): ByteArray {
    val hedr = ByteArray(300)
    floatBytes(1.3f).copyInto(hedr)
    "S3".encodeToByteArray().copyInto(hedr, 8)
    "Plugin generated by s3-lightfixes".encodeToByteArray().copyInto(hedr, 40)
    ByteBuilder(4).appendU32((lights.size + cells.size).toLong()).toByteArray().copyInto(hedr, 296)
    val h = ByteBuilder().sub("HEDR", hedr)
    for ((name, size) in masters) h.sub("MAST", codec.encode(name) + 0).sub("DATA", ByteBuilder(8).appendU64(size).toByteArray())
    val out = ByteBuilder(1 shl 16)
    out.record("TES3", 0, h.toByteArray())
    for (l in lights.sortedBy { it.id.lowercase() }) {
        val body = ByteBuilder()
        for (s in l.subs) {
            // Empty optional strings (a bare NUL) are not written, as the game's own tools do.
            if (s.tag in optionalStrings && s.data.all { it == 0.toByte() }) continue
            body.sub(s.tag, if (s.tag == "LHDT") l.data.toBytes(s.data) else s.data)
        }
        out.record("LIGH", l.flags and 0x20L.inv(), body.toByteArray())
    }
    for (c in cells.sortedBy { it.id.lowercase() }) {
        val body = ByteBuilder()
        for (s in c.meta) body.sub(s.tag, s.data)
        out.record("CELL", c.flags and 0x20L.inv(), body.toByteArray())
    }
    return out.toByteArray()
}
