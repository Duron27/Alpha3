package org.alpha3.launcher.mods.downloader

import okio.buffer
import okio.use
import org.alpha3.launcher.io.File

/**
 * An openmw.cfg's load order, for the organizer: its data folders, archives and plugins, each in its own order and
 * each on or off. Everything else in the file (fallback values, comments) stays where it is: the entries of a kind
 * go back into the places that kind had, in their new order.
 */
class LoadOrder(private val lines: List<String>) {
    enum class Kind(val prefix: String, val title: String) {
        DATA("data=", "Data folders"),
        ARCHIVE("fallback-archive=", "Archives"),
        CONTENT("content=", "Plugins"),
        GROUNDCOVER("groundcover=", "Groundcover"),
    }

    data class Entry(val kind: Kind, val value: String, val enabled: Boolean) {
        /** The name shown: a plugin or archive as it is, a folder as its last two parts. */
        val label: String get() = if (kind != Kind.DATA) value else value.removeSurrounding("\"").trimEnd('/')
            .split('/').takeLast(2).joinToString("/")

        fun line() = (if (enabled) "" else "#") + kind.prefix + value
    }

    private fun parse(line: String): Entry? {
        val t = line.trim()
        val off = t.startsWith("#")
        val body = t.removePrefix("#").trim()
        val kind = Kind.entries.firstOrNull { body.startsWith(it.prefix) } ?: return null
        // A comment that only looks like an entry ("# content= lines below") is not one
        if (off && body.substringAfter('=').isBlank()) return null
        return Entry(kind, body.substringAfter('=').trim(), !off)
    }

    /** The entries of each kind, in order. */
    val entries: Map<Kind, List<Entry>> = Kind.entries.associateWith { kind -> lines.mapNotNull(::parse).filter { it.kind == kind } }

    /** The file again, with the entries of each kind as [order] has them. */
    fun write(order: Map<Kind, List<Entry>>): List<String> {
        val queues = Kind.entries.associateWith { ArrayDeque(order[it].orEmpty()) }
        val out = mutableListOf<String>()
        for (line in lines) {
            val entry = parse(line)
            if (entry == null) { out += line; continue }
            queues.getValue(entry.kind).removeFirstOrNull()?.let { out += it.line() }
        }
        // Entries a kind did not have places for (none, normally) go after that kind's last line
        for (kind in Kind.entries) {
            val rest = queues.getValue(kind)
            if (rest.isEmpty()) continue
            val at = out.indexOfLast { parse(it)?.kind == kind }
            if (at >= 0) out.addAll(at + 1, rest.map { it.line() }) else out.addAll(rest.map { it.line() })
        }
        return out
    }

    companion object {
        val contentExtensions = setOf("esp", "esm", "omwaddon", "omwgame", "omwscripts")

        private val dataFolderNames = setOf(
            "meshes", "textures", "icons", "sound", "music", "fonts", "splash", "bookart", "scripts", "shaders",
            "l10n", "video", "grass", "distantland",
        )

        fun isDataFolder(dir: File): Boolean = dir.listFiles().orEmpty().any { child ->
            val name = child.name.lowercase()
            if (child.isDirectory) name in dataFolderNames
            else name.substringAfterLast('.', "") in contentExtensions || name.endsWith(".bsa")
        }

        /** Masters before plugins, as OpenMW needs them first: esm/omwgame, then esp/omwaddon, then scripts. */
        val fileOrder = compareBy<File> { rank(it.name) }.thenBy { it.name.lowercase() }

        private fun rank(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "esm", "omwgame" -> 0
            "esp", "omwaddon" -> 1
            else -> 2
        }

        private val vanilla = listOf("morrowind.esm", "tribunal.esm", "bloodmoon.esm")

        /**
         * Sorts plugins so that each loads after the masters it names in its header, keeping the order it had
         * otherwise: the game's own three first, then each plugin after its masters. [find] gives a plugin's file
         * (null when it is not installed: it then keeps its place). The order is only as good as the headers;
         * LOOT's masterlist rules would go here.
         */
        fun sortPlugins(plugins: List<Entry>, find: (String) -> File?): List<Entry> {
            val byName = plugins.associateBy { it.value.lowercase() }
            val masters = plugins.associate { p -> p.value.lowercase() to (find(p.value)?.let(::mastersOf).orEmpty()) }
            val done = LinkedHashSet<String>()
            val visiting = HashSet<String>()
            fun visit(name: String) {
                if (name in done || name !in byName || !visiting.add(name)) return
                for (m in masters[name].orEmpty()) visit(m.lowercase())
                visiting.remove(name)
                done += name
            }
            vanilla.forEach(::visit)
            plugins.forEach { visit(it.value.lowercase()) }
            return done.map { byName.getValue(it) }
        }

        /** The masters a TES3 plugin's header names (its MAST subrecords); empty for anything else. */
        fun mastersOf(file: File): List<String> = try {
            file.source().buffer().use { src ->
                if (src.readUtf8(4) != "TES3") return emptyList()
                val size = src.readIntLe().toLong()
                src.skip(8)
                val masters = mutableListOf<String>()
                var read = 0L
                while (read < size) {
                    val tag = src.readUtf8(4)
                    val len = src.readIntLe().toLong()
                    read += 8 + len
                    if (tag == "MAST") masters += src.readUtf8(len).trimEnd('\u0000') else src.skip(len)
                }
                masters
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
