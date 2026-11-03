package org.alpha3.launcher.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.ui.controls.UIStateManager.gold

/**
 * The game's virtual file system as a profile's openmw.cfg makes it: the engine's own resources/vfs, the archives
 * (fallback-archive=, in order) and the data folders (data=, in order). A loose file wins over any archive, and a later
 * folder over an earlier one, as in OpenMW. Every file knows all the places that have it, so overrides show.
 */
class Vfs private constructor(
    /** The sources, lowest priority first: what each provider index means. */
    val sources: List<Source>,
    /** Every file by its lower-case path: the indices of the sources that have it, the winner last. */
    private val files: Map<String, Entry>,
    /** Every folder by its lower-case path: its subfolders' and files' lower-case names. */
    private val folders: Map<String, Pair<Set<String>, Set<String>>>,
) {
    data class Source(val label: String, val path: String, val isArchive: Boolean)

    class Entry(val path: String, val providers: List<Int>, val size: Long)

    val fileCount get() = files.size

    fun folder(path: String): Pair<List<String>, List<Entry>> {
        val (dirs, names) = folders[path.lowercase()] ?: return emptyList<String>() to emptyList()
        val prefix = if (path.isEmpty()) "" else "${path.lowercase()}/"
        return dirs.sorted() to names.mapNotNull { files["$prefix$it"] }.sortedBy { it.path.lowercase() }
    }

    fun search(text: String, limit: Int = 500): List<Entry> {
        val needle = text.lowercase()
        return files.entries.asSequence().filter { it.key.contains(needle) }.take(limit).map { it.value }
            .sortedBy { it.path.lowercase() }.toList()
    }

    companion object {
        /** Reads the VFS of the openmw.cfg at [cfgPath]; [onProgress] says what it is reading. */
        fun load(cfgPath: String, onProgress: (String) -> Unit): Vfs {
            val lines = File(cfgPath).takeIf { it.isFile }?.readLines().orEmpty()
            fun value(line: String) = line.substringAfter('=').trim().let {
                // openmw.cfg quoting: "..." with &" and && inside
                if (it.startsWith("\"")) it.removePrefix("\"").substringBeforeLast("\"").replace("&\"", "\"").replace("&&", "&") else it
            }
            val dataDirs = lines.filter { it.trim().startsWith("data=") }.map { value(it.trim()) }
            val archives = lines.filter { it.trim().startsWith("fallback-archive=") }.map { value(it.trim()) }

            val sources = mutableListOf<Source>()
            val files = HashMap<String, Entry>()
            val folders = HashMap<String, Pair<MutableSet<String>, MutableSet<String>>>()
            fun addPath(path: String, source: Int, size: Long) {
                val clean = path.replace('\\', '/').trim('/')
                if (clean.isEmpty()) return
                val key = clean.lowercase()
                val old = files[key]
                files[key] = Entry(old?.path ?: clean, (old?.providers.orEmpty() - source) + source, size)
                var parent = ""
                for (part in key.split('/').dropLast(1)) {
                    val child = if (parent.isEmpty()) part else "$parent/$part"
                    folders.getOrPut(parent) { mutableSetOf<String>() to mutableSetOf() }.first += part
                    parent = child
                }
                folders.getOrPut(parent) { mutableSetOf<String>() to mutableSetOf() }.second += key.substringAfterLast('/')
            }

            // The engine's own files first, then the archives (any loose file beats them), then the data folders
            val builtin = "${OpenMWPaths.USER_RESOURCES}/vfs"
            val dirOrder = listOf(builtin).filter { File(it).isDirectory } + dataDirs
            val found = HashMap<String, String>() // archive name -> where it is, the last data folder that has it
            for (dir in dataDirs) for (archive in archives) {
                val f = File(dir, archive)
                if (f.isFile) found[archive.lowercase()] = f.path
            }
            for (archive in archives) {
                val path = found[archive.lowercase()] ?: continue
                onProgress("Reading $archive")
                sources += Source(archive, path, isArchive = true)
                val index = sources.lastIndex
                try {
                    for ((name, size) in Bsa.list(path)) addPath(name, index, size)
                } catch (e: Exception) {
                    sources[index] = Source("$archive (unreadable: ${e.message})", path, true)
                }
            }
            for (dir in dirOrder) {
                val root = File(dir)
                if (!root.isDirectory) continue
                onProgress("Reading ${dir.substringAfterLast('/')}")
                sources += Source(if (dir == builtin) "resources/vfs (the engine's)" else dir.trimEnd('/').substringAfterLast('/'), dir, false)
                val index = sources.lastIndex
                val base = root.path.trimEnd('/').length + 1
                for (f in root.walk()) if (f.isFile) addPath(f.path.substring(base), index, f.length())
            }
            return Vfs(sources, files, folders.mapValues { (_, v) -> v.first.toSet() to v.second.toSet() })
        }
    }
}

/** Morrowind's BSA archives (version 0x100): the names and sizes of the files in one. */
internal object Bsa {
    fun list(path: String): List<Pair<String, Long>> = File(path).source().buffer().use { src ->
        val version = src.readIntLe()
        if (version != 0x100) throw IllegalArgumentException("not a Morrowind BSA")
        val hashOffset = src.readIntLe().toLong()
        val count = src.readIntLe()
        val sizes = LongArray(count)
        for (i in 0 until count) {
            sizes[i] = src.readIntLe().toLong() and 0xFFFFFFFFL
            src.readIntLe() // offset
        }
        val nameOffsets = IntArray(count) { src.readIntLe() }
        // The names block runs to the hash table: 12 bytes header, then 8 per record and 4 per name offset before it
        val namesStart = 8L * count + 4L * count
        val namesLength = hashOffset - namesStart
        val names = src.readByteArray(namesLength)
        List(count) { i ->
            val start = nameOffsets[i]
            var end = start
            while (end < names.size && names[end] != 0.toByte()) end++
            names.decodeToString(start, end).replace('\\', '/') to sizes[i]
        }
    }
}

/** What a start-folder chooser hands back to mean "the profile's VFS" instead of a folder. */
const val VFS_BROWSER_PATH = "alpha3-vfs:"

/** Browses the VFS of a profile (the active one first); a file shows every place that has it, the winner first. */
@Composable
fun VfsBrowserPopup(onDismiss: () -> Unit) {
    var profile by remember { mutableStateOf(Profiles.active) }
    var vfs by remember { mutableStateOf<Vfs?>(null) }
    var progress by remember { mutableStateOf("Reading the profile's openmw.cfg") }
    var path by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Vfs.Entry?>(null) }
    var pickProfile by remember { mutableStateOf(false) }
    val dim = White.copy(alpha = 0.6f)

    LaunchedEffect(profile) {
        vfs = null
        path = ""
        vfs = withContext(Dispatchers.IO) {
            Vfs.load(File(Profiles.folderOf(profile), "openmw.cfg").path) { progress = it }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path.isNotEmpty() && query.isBlank()) {
                        IconButton(onClick = { path = path.substringBeforeLast('/', "") }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up", tint = gold)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("VFS: ${if (profile == Profiles.DEFAULT) "Default profile" else profile}", color = gold, fontWeight = FontWeight.Bold)
                        Text("/$path", color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { pickProfile = true }) { Text("Profile", color = gold) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = gold) }
                }
                val v = vfs
                if (v == null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = gold)
                        Spacer(Modifier.width(12.dp))
                        Text(progress, color = dim, fontSize = 13.sp)
                    }
                    return@Column
                }
                Text("${v.fileCount} files from ${v.sources.size} folders and archives", color = dim, fontSize = 12.sp)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Find a file (part of its path)") }, modifier = Modifier.fillMaxWidth(),
                )
                val (dirs, entries) = remember(v, path, query) {
                    if (query.length >= 2) emptyList<String>() to v.search(query) else v.folder(path)
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(dirs) { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { path = if (path.isEmpty()) name else "$path/$name" }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, null, tint = gold)
                            Spacer(Modifier.width(8.dp))
                            Text(name, color = White)
                        }
                    }
                    items(entries) { entry ->
                        val winner = v.sources[entry.providers.last()]
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = entry }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = dim)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (query.length >= 2) entry.path else entry.path.substringAfterLast('/'), color = White, fontSize = 14.sp)
                                Text(
                                    winner.label + if (entry.providers.size > 1) " · overrides ${entry.providers.size - 1}" else "",
                                    color = if (entry.providers.size > 1) gold else dim, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider(color = White.copy(alpha = 0.06f))
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        val v = vfs ?: return@let
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.path.substringAfterLast('/')) },
            text = {
                Column {
                    Text(entry.path, fontSize = 12.sp)
                    Text("${entry.size} bytes", fontSize = 12.sp)
                    Text("Used from, then what it overrides:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    for ((n, i) in entry.providers.asReversed().withIndex()) {
                        val s = v.sources[i]
                        Text((if (n == 0) "✔ " else "  ") + s.label + if (s.isArchive) " (archive)" else "", fontSize = 13.sp)
                        Text(s.path, fontSize = 10.sp, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }

    if (pickProfile) {
        AlertDialog(
            onDismissRequest = { pickProfile = false },
            title = { Text("Which profile?") },
            text = {
                LazyColumn {
                    items((listOf(Profiles.DEFAULT) + Profiles.list()).distinct()) { p ->
                        Text(
                            if (p == Profiles.DEFAULT) "Default profile" else p,
                            fontWeight = if (p == profile) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { profile = p; pickProfile = false }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickProfile = false }) { Text("Cancel") } },
        )
    }
}
