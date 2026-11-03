package org.alpha3.launcher.mods.downloader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.Log

/**
 * A mod list made in the app, as it is kept and shared: its mods, and its openmw.cfg load order, which the
 * organizer arranges. Mod folders in [cfg] are written as `${MODS}/<category>/<folder>` and the game's Data Files as
 * `${GAME}`, so the list works on any device; a line starting with `#` is switched off.
 */
@Serializable
data class SharedModList(
    val format: String = CustomLists.FORMAT,
    val version: Int = 1,
    val name: String,
    val description: String = "",
    val mods: List<ModDesc> = emptyList(),
    val cfg: List<String> = emptyList(),
)

/**
 * The mod lists made in the app (OpenMW/created/<name>.json, [SharedModList]) and the Nexus mods added by hand to the
 * site's lists (OpenMW/added/<list>.json, the mods only). Both are kept in files of their own, so a sync of the site's
 * lists keeps them, and written to the database at once, so the downloader shows them straight away.
 *
 * The site's config for a list does not know mods added to it: once a run is done their folders and plugins are added
 * to the list's generated openmw.cfg ([finishConfig]). A list made in the app has no site config at all; its own
 * [SharedModList.cfg] is the load order, and the run writes the openmw.cfg from it.
 */
object CustomLists {
    const val FORMAT = "alpha3-modlist"
    const val MODS = "\${MODS}"
    const val GAME = "\${GAME}"

    /** Where an added mod's archives unpack, under OpenMW/Mods. */
    private const val CATEGORY = "Nexus"

    private val createdDir get() = "$modStorage/created"
    private val addedDir get() = "$modStorage/added"
    private fun createdFile(name: String) = File(createdDir, "${fileSafe(name)}.json")
    private fun addedFile(name: String) = File(addedDir, "${fileSafe(name)}.json")
    private fun fileSafe(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")

    /** The names of the lists made in the app; changes when one is made, imported or deleted. */
    val created = mutableStateListOf<String>()

    /** Goes up on every change, for the screens that show the lists. */
    var revision by mutableIntStateOf(0)
        private set

    private fun changed() { revision++ }

    fun refresh() {
        val names = File(createdDir).listFiles { it.name.endsWith(".json") }.orEmpty()
            .mapNotNull { read(it)?.name }.sortedBy { it.lowercase() }
        created.clear()
        created.addAll(names)
        changed()
    }

    fun isCreated(name: String) = createdFile(name).isFile

    private fun read(file: File): SharedModList? = try {
        ModListManager.json.decodeFromString<SharedModList>(file.readText())
    } catch (e: Exception) {
        Log.e("CustomLists", "Could not read ${file.path}: ${e.message}")
        null
    }

    fun load(name: String): SharedModList? = createdFile(name).takeIf { it.isFile }?.let(::read)

    private fun save(list: SharedModList) {
        File(createdDir).mkdirs()
        createdFile(list.name).writeText(ModListManager.json.encodeToString(list))
        changed()
    }

    /** The mods of [list] that this file keeps: all of a list made in the app, the added ones of a site's list. */
    fun modsOf(list: String): List<ModDesc> = load(list)?.mods ?: try {
        val file = addedFile(list)
        if (file.isFile) ModListManager.json.decodeFromString<List<ModDesc>>(file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.e("CustomLists", "Could not read the additions of $list: ${e.message}")
        emptyList()
    }

    private fun setMods(list: String, mods: List<ModDesc>) {
        val own = load(list)
        if (own != null) { save(own.copy(mods = mods)); return }
        val file = addedFile(list)
        if (mods.isEmpty()) file.delete() else {
            File(addedDir).mkdirs()
            file.writeText(ModListManager.json.encodeToString(mods))
        }
        changed()
    }

    /** Every list's mods, for the database load ([ModListManager.downloadAndParseModList]). */
    fun allForDatabase(): List<Pair<String, List<ModDesc>>> {
        val own = File(createdDir).listFiles { it.name.endsWith(".json") }.orEmpty().mapNotNull(::read).map { it.name to it.mods }
        val added = File(addedDir).listFiles { it.name.endsWith(".json") }.orEmpty().mapNotNull { file ->
            try {
                file.nameWithoutExtension to ModListManager.json.decodeFromString<List<ModDesc>>(file.readText())
            } catch (e: Exception) {
                null
            }
        }
        return own + added
    }

    // ---- lists

    /** Why [name] cannot be a new list's name, or null when it can. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Give the list a name"
        name.length > 60 -> "The name is too long"
        name.contains('"') || name.contains('/') || name.contains('\\') -> "The name cannot contain \" / or \\"
        isCreated(name) -> "You already have a list called $name"
        name in ModListManager.availableLists -> "$name is one of modding-openmw.com's lists"
        else -> null
    }

    /** A new, empty list: the game's own files only. */
    fun create(name: String, description: String = "") {
        nameProblem(name)?.let { throw IOException(it) }
        save(SharedModList(name = name, description = description, cfg = vanillaCfg()))
        refresh()
    }

    private fun vanillaCfg() = listOf(
        "data=\"$GAME\"",
        "fallback-archive=Morrowind.bsa",
        "fallback-archive=Tribunal.bsa",
        "fallback-archive=Bloodmoon.bsa",
        "content=Morrowind.esm",
        "content=Tribunal.esm",
        "content=Bloodmoon.esm",
    )

    /** Deletes a list made in the app: its file, and its place in the database (mods no other list has go too). */
    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        createdFile(name).delete()
        val dao = ModDatabase.getDatabase().modDao()
        for (mod in dao.getModsByModList(name)) dropFromList(dao, mod, name)
        refresh()
    }

    private suspend fun dropFromList(dao: ModDao, mod: ModDesc, list: String) {
        val lists = mod.onLists - list
        if (lists.isEmpty()) dao.deleteMod(mod)
        else dao.insertOrReplace(listOf(mod.copy(
            onLists = lists,
            downloadInfo = mod.downloadInfo.map { it.copy(sourceLists = it.sourceLists - list) }.filter { it.sourceLists.isNotEmpty() },
        )))
    }

    // ---- mods

    /** The lists that [modId] is on, the site's included. */
    suspend fun listsOf(modId: Int): Set<String> = withContext(Dispatchers.IO) {
        ModDatabase.getDatabase().modDao().getModsByModId(modId.toString()).flatMap { it.onLists }.toSet()
    }

    /** True when [modId] is on [list] through this app (so it can be taken off again). */
    fun has(list: String, modId: Int) = modsOf(list).any { it.modId == modId.toString() }

    /** Puts [file] of [mod] on [list]: it downloads, unpacks and is enabled with the list's next run. */
    suspend fun add(list: String, mod: NexusBrowse.Mod, file: NexusBrowse.ModFile) = withContext(Dispatchers.IO) {
        val dao = ModDatabase.getDatabase().modDao()
        val fileId = file.fileId.toString()
        // A mod a site's list has already keeps its entry, and the very same file is downloaded and unpacked once
        val existing = dao.getModsByModId(mod.modId.toString()).firstOrNull()
        val reused = existing?.downloadInfo?.firstOrNull { it.nexusFileId == fileId || it.fileName == file.fileName }
        val info = (reused ?: DownloadInfo(
            fileName = file.fileName,
            nexusFileId = fileId,
            fileSize = file.sizeKb.toString(),
            extractTo = folderName(mod.name, mod.modId),
        )).let { portable(it) }.copy(sourceLists = listOf(list))
        val entry = (existing ?: ModDesc(
            modId = mod.modId.toString(),
            slug = "nexus-${mod.modId}",
            name = mod.name,
            author = mod.author.ifBlank { mod.uploader },
            description = mod.summary,
            url = mod.pageUrl,
            category = CATEGORY,
            galleryUrls = listOfNotNull(mod.pictureUrl),
        )).copy(onLists = listOf(list), downloadInfo = listOf(info))

        // One file per mod and list: picking another one replaces it
        setMods(list, modsOf(list).filter { it.modId != entry.modId } + entry)
        putInDatabase(dao, list, entry)
    }

    /** Writes [entry], which is on [list], to the database, next to what other lists have of the same mod. */
    private suspend fun putInDatabase(dao: ModDao, list: String, entry: ModDesc) {
        val current = dao.getModsBySlugs(listOf(entry.slug)).firstOrNull()
        if (current == null) {
            dao.insertOrReplace(listOf(entry))
            return
        }
        val info = entry.downloadInfo
        val others = current.downloadInfo
            .map { it.copy(sourceLists = it.sourceLists - list) }
            .filter { old -> old.sourceLists.isNotEmpty() && info.none { it.fileName == old.fileName } }
        val merged = info.map { new ->
            val old = current.downloadInfo.firstOrNull { it.fileName == new.fileName }
            (old ?: new).copy(sourceLists = (old?.sourceLists.orEmpty() - list + list).distinct())
        }
        dao.insertOrReplace(listOf(current.copy(onLists = (current.onLists + list).distinct(), downloadInfo = others + merged)))
    }

    /** Takes a mod that this app put on [list] off it again. */
    suspend fun remove(list: String, modId: Int) = withContext(Dispatchers.IO) {
        val id = modId.toString()
        val mods = modsOf(list)
        val entry = mods.firstOrNull { it.modId == id } ?: return@withContext
        setMods(list, mods.filter { it.modId != id })
        val dao = ModDatabase.getDatabase().modDao()
        dao.getModsBySlugs(listOf(entry.slug)).firstOrNull()?.let { dropFromList(dao, it, list) }
    }

    private fun folderName(name: String, modId: Int): String {
        val clean = name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().replace(Regex("\\s+"), " ").take(60)
        return clean.ifBlank { "mod-$modId" }
    }

    /** A file entry without what belongs to this device or this account (an expiring link, sizes on disk). */
    private fun portable(info: DownloadInfo) = info.copy(
        directDownload = null,
        sourceLists = emptyList(),
        actions = emptyList(),
        actionsByList = emptyMap(),
        extractedSizeBytes = null,
        versions = emptyList(),
    )

    // ---- sharing

    /** [name] as a file to share: its mods and its load order. */
    fun export(name: String): String {
        val list = load(name) ?: throw IOException("No list called $name")
        val shared = list.copy(mods = list.mods.map { mod ->
            mod.copy(onLists = emptyList(), downloadInfo = mod.downloadInfo.map(::portable))
        })
        return ModListManager.json.encodeToString(shared)
    }

    /**
     * Makes a list from a shared file (see [export]). A list of the same name gets a number after it. Returns the
     * new list's name.
     */
    suspend fun import(text: String): String = withContext(Dispatchers.IO) {
        val shared = try {
            ModListManager.json.decodeFromString<SharedModList>(text)
        } catch (e: Exception) {
            throw IOException("This is not a mod list file (${e.message?.take(120)})")
        }
        if (shared.format != FORMAT) throw IOException("This is not an Alpha3 mod list file")
        var name = shared.name.trim().ifBlank { "Imported list" }
        if (nameProblem(name) != null) {
            var n = 2
            while (nameProblem("$name ($n)") != null) n++
            name = "$name ($n)"
        }
        val mods = shared.mods.map { mod ->
            mod.copy(onLists = listOf(name), downloadInfo = mod.downloadInfo.map { portable(it).copy(sourceLists = listOf(name)) })
        }
        save(shared.copy(name = name, mods = mods, cfg = shared.cfg.ifEmpty { vanillaCfg() }))
        val dao = ModDatabase.getDatabase().modDao()
        for (mod in mods) putInDatabase(dao, name, mod)
        refresh()
        name
    }

    // ---- the load order

    /** The game's Data Files folder, for `${GAME}`. */
    suspend fun gameDataFiles(): String? =
        GameFilesPreferences.getNullablePreference(GameFilesPreferences.GAME_FILES_URI_KEY).first()?.let { "$it/Data Files" }

    fun expand(line: String, game: String?): String =
        line.replace(MODS, "$modStorage/Mods").let { if (game != null) it.replace(GAME, game) else it }

    /** Saves a list's load order, as the organizer arranged it. */
    fun saveCfg(name: String, cfg: List<String>) {
        val list = load(name) ?: return
        save(list.copy(cfg = cfg))
    }

    /**
     * Once a run of [list] is done: a list made in the app gets its openmw.cfg written from its load order (with
     * what its mods brought that the order does not have yet added to it); a site's list gets the mods added to it
     * by hand added to its generated cfg at [sitePath]. Returns the cfg to install, or null when there is none.
     */
    fun finishConfig(list: String, sitePath: String?, game: String?): String? {
        if (Wabbajack.isWabbajack(list)) return Wabbajack.finishConfig(list, game)
        val own = load(list)
        if (own == null) {
            if (sitePath == null || !File(sitePath).isFile) return sitePath
            val added = modsOf(list)
            if (added.isEmpty()) return sitePath
            val cfg = File(sitePath)
            val lines = cfg.readLines().dropLastWhile { it.isBlank() }
            val merged = merge(lines, found(added).map { expand(it, game) })
            if (merged != lines) cfg.writeText(merged.joinToString("\n", postfix = "\n"))
            return sitePath
        }
        val order = merge(own.cfg, found(own.mods))
        if (order != own.cfg) saveCfg(list, order)
        val out = File(modStorage, "${fileSafe(list)}.cfg")
        out.writeText(render(order, game))
        return out.path
    }

    /** A complete openmw.cfg from a load order: the game's fallback values from the default profile, then the order. */
    fun render(order: List<String>, game: String?): String {
        val base = File(OpenMWPaths.BASE_CONFIG, "openmw.cfg")
        val fallback = if (base.isFile) base.readLines().filter {
            val t = it.trim()
            t.startsWith("fallback=") || t.startsWith("encoding=")
        } else emptyList()
        return buildString {
            append("# Made by Alpha3 from the mod list's load order. Arrange it in the mod downloader's list organizer.\n\n")
            fallback.forEach { append(it).append('\n') }
            append('\n')
            order.forEach { append(expand(it, game)).append('\n') }
        }
    }

    /**
     * Puts a list's load order into the openmw.cfg of the profile it was installed to, if it was, so the game uses
     * the organizer's order without another run. Returns that file, or null.
     */
    fun applyToProfile(list: String, game: String?): String? {
        val own = load(list) ?: return null
        val target = File(Profiles.folderOf(Profiles.sanitize(list)), "openmw.cfg")
        if (!target.isFile) return null
        target.writeText(render(own.cfg, game))
        return target.path
    }

    /** What the unpacked files of [mods] bring: data folders, archives and plugins, as cfg lines. */
    fun found(mods: List<ModDesc>): List<String> {
        val lines = mutableListOf<String>()
        for (mod in mods) for (info in mod.downloadInfo) {
            val folder = "${mod.category}/${info.extractTo}"
            for (root in dataRoots(File("$modStorage/Mods", folder))) {
                val inside = root.path.removePrefix("$modStorage/Mods")
                lines += "data=\"$MODS$inside\""
                for (child in root.listFiles().orEmpty().filter { it.isFile }.sortedWith(LoadOrder.fileOrder)) {
                    val ext = child.name.substringAfterLast('.', "").lowercase()
                    if (ext == "bsa") lines += "fallback-archive=${child.name}"
                    else if (ext in LoadOrder.contentExtensions) lines += "content=${child.name}"
                }
            }
        }
        return lines
    }

    /**
     * [order] with the lines of [found] that it lacks: data folders after its last data folder, archives after its
     * last archive, plugins at the end. A line it has switched off (`#...`) counts as had.
     */
    fun merge(order: List<String>, found: List<String>): List<String> {
        val key = { line: String -> line.trim().removePrefix("#").trim().lowercase() }
        val have = order.map(key).toMutableSet()
        val haveContent = order.map(key).filter { it.startsWith("content=") }.map { it.substringAfter('=').trim() }.toMutableSet()
        val lines = order.toMutableList()
        val data = mutableListOf<String>()
        val archives = mutableListOf<String>()
        val content = mutableListOf<String>()
        for (line in found) {
            val k = key(line)
            when {
                k in have -> {}
                k.startsWith("data=") -> data += line
                k.startsWith("fallback-archive=") -> archives += line
                k.startsWith("content=") -> if (haveContent.add(k.substringAfter('='))) content += line
            }
            have += k
        }
        fun insertAfterLast(prefix: String, new: List<String>) {
            if (new.isEmpty()) return
            val at = lines.indexOfLast { key(it).startsWith(prefix) }
            if (at >= 0) lines.addAll(at + 1, new) else lines.addAll(new)
        }
        insertAfterLast("data=", data)
        insertAfterLast("fallback-archive=", archives)
        insertAfterLast("content=", content)
        return lines
    }

    /**
     * The data folders in an unpacked archive: the folder itself when it is one, otherwise the shallowest ones below
     * it (an archive's "Data Files" folder, or a FOMOD's numbered options, in name order).
     */
    fun dataRoots(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        var level = listOf(root)
        repeat(4) {
            val found = level.filter(LoadOrder::isDataFolder)
            if (found.isNotEmpty()) return found
            level = level.flatMap { dir ->
                dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.equals("fomod", ignoreCase = true) }
                    .sortedBy { it.name.lowercase() }
            }
            if (level.isEmpty()) return emptyList()
        }
        return emptyList()
    }
}
