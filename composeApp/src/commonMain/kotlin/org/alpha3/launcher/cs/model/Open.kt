package org.alpha3.launcher.cs.model

import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolNotFound
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.Vfs
import org.alpha3.launcher.tools.openmw.fail
import org.alpha3.launcher.tools.openmw.joinPath
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.tools.render.MwAssets

/** A content file of the load order and where it is on disk (null: not found in any data folder). */
internal class LoadOrderEntry(val name: String, val path: String?) {
    val isMaster get() = name.endsWith(".esm", ignoreCase = true)
    val isPlugin get() = Regex("\\.(esm|esp|omwaddon|omwgame)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

/** The game's load order and file layout, read from openmw.cfg. */
internal class Workspace(val fs: ToolFs, platform: ToolPlatform, cfg: String) {
    private val omw = readOpenMW(cfg, platform, fs)
    val codec: TextCodec = TextCodec.forName(omw.encoding)
    private val vfs = Vfs(omw.data, emptyList(), codec, fs)
    val dataDirs: List<String> get() = omw.data

    fun find(name: String): String? = try { vfs.loose(name).path } catch (_: ToolNotFound) { null }

    val loadOrder: List<LoadOrderEntry> by lazy {
        omw.content.distinct().map { LoadOrderEntry(it, find(it)) }.filter { it.isPlugin }
    }

    /** [plugin] over its masters, each master indexed (this reads them all once). */
    fun open(plugin: PluginFile, progress: (String) -> Unit = {}): CsDocument {
        val masters = plugin.masters.mapIndexed { i, (name, _) ->
            val path = find(name) ?: fail("master $name of ${plugin.path} is not in any data folder")
            progress("Reading $name")
            MasterIndex(fs, path, i)
        }
        return CsDocument(fs, codec, plugin, masters)
    }

    fun openFile(path: String, progress: (String) -> Unit = {}): CsDocument = open(readPlugin(fs, path), progress)

    /** A new plugin [name] in [dir] over [masters] (load order names), not written until saved. */
    fun create(dir: String, name: String, author: String, description: String, masters: List<String>, progress: (String) -> Unit = {}): CsDocument {
        val sized = masters.map { m -> m to (find(m)?.let { fs.size(it) } ?: fail("master $m is not in any data folder")) }
        fs.mkdirs(dir)
        val path = joinPath(dir, name)
        if (fs.isFile(path)) fail("$name already exists in $dir")
        return open(PluginFile(path, newHeader(author, description, sized, codec), ArrayList()), progress)
    }

    /** Meshes, textures and icons from the data folders and their archives, later folders winning (as in the game). */
    val assets: MwAssets by lazy { MwAssets(fs, omw.data).also { it.maxTextureSize = 512 } }

    fun masterSize(name: String): Long? = find(name)?.let { fs.size(it) }
}
