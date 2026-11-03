package org.alpha3.launcher.mods.downloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.Log

/**
 * Wabbajack lists in the mod downloader. A Wabbajack list for Morrowind is a Mod Organizer setup published as one
 * `.wabbajack` file: the archives to download, where every installed file comes from (a path in one of those
 * archives, or a file of the list's own), and the profile's openmw.cfg, which is the author's load order.
 *
 * The list shows in the downloader's list picker like the site's lists. Its Download button first fetches the list's
 * newest `.wabbajack` from Nexus (when it changed) and turns it into a plan (cpp/modtools, see wabbajack.rs), which
 * puts the archives on the list as ordinary Nexus or web downloads. The run then downloads them as for any list, but
 * unpacks each archive by the plan ([unpack]): only the files the list uses, to the Mod Organizer folders the list
 * puts them in (`OpenMW/Mods/wabbajack/<list>/mods/<mod>`). Once the run is done, the list's own files are written and
 * its openmw.cfg, with its paths moved to this device, becomes the list's profile ([finishConfig]).
 */
object Wabbajack {
    /** A Wabbajack list the downloader offers: [list] is its name in the list picker. */
    data class Source(val list: String, val title: String, val nexusModId: String, val fileNameHas: String)

    val sources = listOf(
        Source("path-of-the-incarnate", "Path of the Incarnate", "53437", "wabbajack"),
    )

    private const val CATEGORY = "wabbajack"

    fun isWabbajack(list: String) = sources.any { it.list == list }
    fun owns(mod: ModDesc) = mod.category.startsWith("$CATEGORY/")

    private fun dir(list: String) = "$modStorage/wabbajack/$list"
    /** Where the list installs: its mods/ folders, as Mod Organizer has them. */
    fun root(list: String) = "$modStorage/Mods/$CATEGORY/$list"

    /** What preparing the list is doing, for the downloader; null when nothing is. */
    var status by mutableStateOf<String?>(null)

    @Serializable
    private data class State(val fileId: String, val fileName: String, val version: String, val profile: String? = null)

    @Serializable
    private data class PlannedArchive(
        val index: Int,
        val hash: String,
        val name: String,
        val size: Long,
        val kind: String,
        val mod_id: Long? = null,
        val file_id: Long? = null,
        val mod_name: String? = null,
        val author: String? = null,
        val version: String? = null,
        val image: String? = null,
        val url: String? = null,
        val files: Int = 0,
    )

    private fun readState(list: String): State? = try {
        File(dir(list), "state.json").takeIf { it.isFile }?.let { ModListManager.json.decodeFromString<State>(it.readText()) }
    } catch (e: Exception) {
        null
    }

    /** The list's mods as the database keeps them, once it was prepared (for [ModListManager.downloadAndParseModList]). */
    fun savedMods(): List<Pair<String, List<ModDesc>>> = sources.mapNotNull { source ->
        val file = File(dir(source.list), "mods.json")
        if (!file.isFile) return@mapNotNull null
        try {
            source.list to ModListManager.json.decodeFromString<List<ModDesc>>(file.readText())
        } catch (e: Exception) {
            Log.e("Wabbajack", "Could not read ${file.path}: ${e.message}")
            null
        }
    }

    /**
     * Makes sure the list's newest version is planned and its archives are in the database, before a run. Fetches the
     * `.wabbajack` from Nexus only when there is a newer one than the planned one.
     */
    suspend fun prepare(list: String) = withContext(Dispatchers.IO) {
        val source = sources.first { it.list == list }
        if (!ModTools.available) throw IOException("This build cannot read Wabbajack lists (the mod tools are missing)")
        try {
            status = "${source.title}: asking Nexus for the newest version"
            val newest = NexusFiles.newest(source.nexusModId) { it.name.contains(source.fileNameHas, ignoreCase = true) }
            val state = readState(list)
            val planned = state?.fileId == newest.fileId && File(dir(list), "plan/archives.json").isFile && File(dir(list), "mods.json").isFile
            if (!planned) fetchAndPlan(source, newest)
            status = "${source.title}: adding its archives to the list"
            val mods = savedMods().firstOrNull { it.first == list }?.second ?: throw IOException("The list's plan is missing")
            val dao = ModDatabase.getDatabase().modDao()
            // An earlier version's archives that this one no longer has leave the list
            for (old in dao.getModsByModList(list)) {
                if (mods.none { it.slug == old.slug }) dao.deleteMod(old)
            }
            dao.mergeLists(listOf(list to mods)) { _, _, error -> if (error != null) throw error }
            status = null
        } catch (e: Exception) {
            status = "${source.title}: ${e.message}"
            throw e
        }
    }

    private suspend fun fetchAndPlan(source: Source, file: NexusFiles.FileInfo) {
        val dir = dir(source.list)
        val work = File(dir, "download")
        work.deleteRecursively()
        work.mkdirs()
        val zip = File(work, file.fileName.ifBlank { "list.zip" })
        val url = NexusFiles.downloadLink(source.nexusModId, file.fileId) { status = "${source.title}: $it" }
        NexusFiles.download(url, zip) { done, total ->
            status = "${source.title} ${file.version}: downloading the list, ${done / 1024} KB" + if (total > 0) " of ${total / 1024} KB" else ""
        }

        // Nexus' file is a zip with the .wabbajack in it, and the .wabbajack is a zip itself
        status = "${source.title}: unpacking the list"
        val wabbajack = if (zip.name.endsWith(".wabbajack", ignoreCase = true)) zip else {
            unpackAll(zip.path, work.path)
            File(work.path).walk().firstOrNull { it.isFile && it.name.endsWith(".wabbajack", ignoreCase = true) }
                ?: throw IOException("Nexus' file has no .wabbajack in it")
        }
        val sourceDir = File(dir, "source")
        sourceDir.deleteRecursively()
        unpackAll(wabbajack.path, sourceDir.path)
        work.deleteRecursively()

        status = "${source.title}: reading the list"
        val request = buildJsonObject {
            put("modlist", "${sourceDir.path}/modlist")
            put("out_dir", "$dir/plan")
        }
        val answer = ModListManager.json.parseToJsonElement(ModTools.wabbajackPlan(request.toString())).jsonObject
        answer["error"]?.jsonPrimitive?.contentOrNull?.let { throw IOException(it) }
        fun str(k: String) = answer[k]?.jsonPrimitive?.contentOrNull
        val unsupported = answer["unsupported_archives"]?.toString().orEmpty()
        if (unsupported.length > 2) throw IOException("The list uses downloads this app cannot fetch: $unsupported")
        appendToExtractLog("Planned ${source.title} ${str("version")}: ${str("archives")} archives, ${str("files")} files, " +
            "${(str("download_bytes")?.toLongOrNull() ?: 0) / 1_000_000_000} GB to download, " +
            "${(str("install_bytes")?.toLongOrNull() ?: 0) / 1_000_000_000} GB installed")

        val archives = ModListManager.json.decodeFromString<List<PlannedArchive>>(File(dir, "plan/archives.json").readText())
        val mods = toMods(source, archives)
        File(dir, "mods.json").writeText(ModListManager.json.encodeToString(mods))
        File(dir, "state.json").writeText(ModListManager.json.encodeToString(
            State(file.fileId, file.fileName, str("version") ?: file.version, str("profile"))))
        archiveIndex.remove(source.list)
    }

    private fun unpackAll(archive: String, target: String) {
        val errors = mutableListOf<String>()
        Archives.extract(
            archive,
            target = { item ->
                val path = item.path.replace('\\', '/').trimStart('/')
                if (item.isFolder || path.isEmpty() || path.split('/').any { it == ".." }) null
                else "$target/$path".also { File(it.substringBeforeLast('/')).mkdirs() }
            },
            onFile = {},
            onError = { errors += it },
        )
        if (errors.isNotEmpty()) throw IOException("Could not unpack ${archive.substringAfterLast('/')}: ${errors.first()}")
    }

    /** The plan's archives as the list's mods: one per Nexus mod (its files as its downloads), one per web download. */
    private fun toMods(source: Source, archives: List<PlannedArchive>): List<ModDesc> {
        val category = "$CATEGORY/${source.list}"
        fun info(a: PlannedArchive, direct: String?) = DownloadInfo(
            fileName = a.name,
            nexusFileId = a.file_id?.toString(),
            directDownload = direct,
            fileSize = (a.size / 1024).toString(),
            extractTo = "mods",
            sourceLists = listOf(source.list),
        )
        val nexus = archives.filter { it.kind == "nexus" && it.mod_id != null }.groupBy { it.mod_id!! }.map { (modId, files) ->
            val first = files.first()
            ModDesc(
                modId = modId.toString(),
                slug = "wj-${source.list}-$modId",
                name = first.mod_name ?: first.name,
                author = first.author.orEmpty(),
                url = "https://www.nexusmods.com/morrowind/mods/$modId",
                category = category,
                galleryUrls = listOfNotNull(first.image),
                onLists = listOf(source.list),
                downloadInfo = files.map { info(it, null) },
            )
        }
        val web = archives.filter { it.kind == "http" && it.url != null }.map { a ->
            ModDesc(
                modId = "wj-${a.index}",
                slug = "wj-${source.list}-web-${a.index}",
                name = a.name.substringBeforeLast('.'),
                url = a.url!!,
                category = category,
                onLists = listOf(source.list),
                downloadInfo = listOf(info(a, a.url)),
            )
        }
        return nexus + web
    }

    // ---- unpacking

    /** The plan's archive index by file name, per list. */
    private val archiveIndex = HashMap<String, Map<String, Int>>()
    private val indexLock = SpinLock()

    private fun indexOf(list: String, fileName: String): Int? {
        val map = indexLock.withLock { archiveIndex[list] } ?: run {
            val loaded = ModListManager.json.decodeFromString<List<PlannedArchive>>(File(dir(list), "plan/archives.json").readText())
                .associate { it.name to it.index }
            indexLock.withLock { archiveIndex[list] = loaded }
            loaded
        }
        return map[fileName]
    }

    private class Target(val to: String, val patch: String?)

    /**
     * Unpacks [archivePath] (a file of [mod], on the Wabbajack list [list]) as the plan says: each file the list uses to
     * every place it goes, the files the list patches patched. [onFile] reports progress. Returns null on success, or
     * what went wrong.
     */
    fun unpack(list: String, info: DownloadInfo, archivePath: String, onFile: (String, Int, Int) -> Unit): String? {
        val index = indexOf(list, info.fileName.orEmpty()) ?: return "The list's plan has no archive ${info.fileName}"
        val root = root(list)
        val wanted = HashMap<String, MutableList<Target>>()
        File(dir(list), "plan/a/$index.tsv").readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val parts = line.split('\t')
            if (parts.size < 2) return@forEach
            wanted.getOrPut(parts[0].lowercase()) { mutableListOf() } += Target(parts[1], parts.getOrNull(2))
        }
        val total = wanted.values.sumOf { it.size }
        val written = HashMap<String, String>() // archive path -> the file it was unpacked to
        val tempDir = "${dir(list)}/tmp/$index"
        val errors = mutableListOf<String>()
        var done = 0
        Archives.extract(
            archivePath,
            target = { item ->
                if (item.isFolder) return@extract null
                val key = item.path.replace('\\', '/').trimStart('/').lowercase()
                val targets = wanted[key] ?: return@extract null
                // Straight to its first plain place; a file that is only patched goes to a temporary file first
                val out = targets.firstOrNull { it.patch == null }?.let { "$root/${it.to}" } ?: "$tempDir/${written.size}"
                File(out.substringBeforeLast('/')).mkdirs()
                written[key] = out
                out
            },
            onFile = { item -> done++; onFile(item.path.substringAfterLast('/'), done, total) },
            onError = { errors += it },
        )
        if (errors.isNotEmpty()) return errors.first()
        val missing = wanted.keys.filter { it !in written }
        if (missing.isNotEmpty()) return "${missing.size} files the list needs are not in ${info.fileName} (first: ${missing.first()})"

        // The other places a file goes, and the patched files
        for ((key, targets) in wanted) {
            val from = File(written.getValue(key))
            for (target in targets) {
                val to = File("$root/${target.to}")
                if (to.path == from.path) continue
                File(to.path.substringBeforeLast('/')).mkdirs()
                if (target.patch == null) {
                    from.copyTo(to, overwrite = true)
                } else {
                    val patch = File(dir(list), "source/${target.patch}")
                    if (!patch.isFile) return "The list's patch ${target.patch} is missing"
                    to.writeBytes(Octodiff.apply(from.readBytes(), patch.readBytes()))
                }
            }
        }
        File(tempDir).deleteRecursively()
        return null
    }

    // ---- the list's own files and its openmw.cfg

    /**
     * After a run: writes the list's own files (the plugins and configs the author made, Mod Organizer's meta.ini)
     * and its openmw.cfg with the paths moved to this device. Returns that cfg, for the list's profile.
     */
    fun finishConfig(list: String, game: String?): String? {
        val dir = dir(list)
        val root = root(list)
        val inline = File(dir, "plan/inline.tsv").takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }
        var cfgSource: File? = null
        for ((id, to) in inline) {
            val from = File(dir, "source/$id")
            if (!from.isFile) continue
            if (to.startsWith("profiles/", ignoreCase = true)) {
                if (to.endsWith("/openmw.cfg", ignoreCase = true) && cfgSource == null) cfgSource = from
                continue
            }
            val target = File("$root/$to")
            File(target.path.substringBeforeLast('/')).mkdirs()
            from.copyTo(target, overwrite = true)
        }
        File("$root/overwrite").mkdirs()
        val cfg = cfgSource ?: return null
        val out = File(modStorage, "$list.cfg")
        out.writeText(movePaths(cfg.readLines(), root, game).joinToString("\n", postfix = "\n"))
        return out.path
    }

    /**
     * The author's openmw.cfg on this device: their game's Data Files becomes this one's, and their Mod Organizer
     * folder (the one that holds mods/ and overwrite) becomes [root].
     */
    internal fun movePaths(lines: List<String>, root: String, game: String?): List<String> {
        // The author's install folder, from the first data line into mods/
        val authorRoot = lines.asSequence()
            .filter { it.startsWith("data=") }
            .map { it.substringAfter('=').trim().removeSurrounding("\"").replace('\\', '/') }
            .firstNotNullOfOrNull { p -> p.indexOf("/mods/", ignoreCase = true).takeIf { it > 0 }?.let { p.substring(0, it) } }
        return lines.map { line ->
            if (!line.startsWith("data=")) return@map line
            val path = line.substringAfter('=').trim().removeSurrounding("\"").replace('\\', '/')
            val moved = when {
                authorRoot != null && path.startsWith(authorRoot, ignoreCase = true) -> root + path.substring(authorRoot.length)
                path.trimEnd('/').endsWith("/Data Files", ignoreCase = true) && game != null -> game
                else -> path
            }
            "data=\"$moved\""
        }
    }
}

/** Octodiff deltas, as Wabbajack ships its patched files: OCTODELTA, a hash of the source, then copy and data commands. */
internal object Octodiff {
    fun apply(source: ByteArray, delta: ByteArray): ByteArray {
        var at = 0
        fun byte() = delta[at++].toInt() and 0xFF
        fun int32(): Int { var v = 0; for (i in 0 until 4) v = v or (byte() shl (8 * i)); return v }
        fun int64(): Long { var v = 0L; for (i in 0 until 8) v = v or (byte().toLong() shl (8 * i)); return v }
        if (delta.size < 9 || delta.decodeToString(0, 9) != "OCTODELTA") throw IOException("Not an Octodiff delta")
        at = 9
        byte() // version
        // The hash algorithm's name, a .NET string: its length in 7-bit groups, then UTF-8
        var nameLength = 0
        var shift = 0
        while (true) { val b = byte(); nameLength = nameLength or ((b and 0x7F) shl shift); if (b < 0x80) break; shift += 7 }
        at += nameLength
        at += int32() // the source's hash
        if (delta.decodeToString(at, at + 3) != ">>>") throw IOException("Octodiff delta without its marker")
        at += 3
        val out = okio.Buffer()
        while (at < delta.size) {
            when (byte()) {
                0x60 -> {
                    val offset = int64()
                    val length = int64()
                    if (offset < 0 || offset + length > source.size) throw IOException("Octodiff copy outside the source")
                    out.write(source, offset.toInt(), length.toInt())
                }
                0x80 -> {
                    val length = int64().toInt()
                    out.write(delta, at, length)
                    at += length
                }
                else -> throw IOException("Unknown Octodiff command at $at")
            }
        }
        return out.readByteArray()
    }
}
