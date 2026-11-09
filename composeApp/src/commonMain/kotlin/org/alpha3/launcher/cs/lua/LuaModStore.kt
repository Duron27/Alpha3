package org.alpha3.launcher.cs.lua

import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.joinPath

/**
 * Where the creator's mods live: one data folder each under <alpha3_cs>/lua/<id>, holding the generated mod and
 * alpha3-luamod.json (the blocks, to open it again). A folder is added to openmw.cfg (data= and content=<id>.omwscripts)
 * when the mod is enabled.
 */
class LuaModStore(private val fs: ToolFs, val root: String) {
    fun folder(id: String) = joinPath(root, identifier(id))
    private fun projectFile(id: String) = joinPath(folder(id), PROJECT)

    fun list(): List<LuaMod> = fs.list(root).orEmpty().sorted().mapNotNull { name ->
        val f = joinPath(joinPath(root, name), PROJECT)
        if (!fs.isFile(f)) null else runCatching { luaModFromJson(fs.readText(f)) }.getOrNull()
    }

    fun exists(id: String) = fs.isDirectory(folder(id))

    fun load(id: String): LuaMod = luaModFromJson(fs.readText(projectFile(id)))

    /**
     * Writes the mod: its project file and every generated file. Files the previous save made that are no longer
     * generated (a script renamed or removed) are deleted; anything else in the folder is left alone.
     */
    fun save(mod: LuaMod, files: Map<String, String>) {
        val dir = folder(mod.id)
        fs.mkdirs(dir)
        val old = runCatching { fs.readText(joinPath(dir, MANIFEST)).lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        for ((path, text) in files) write(joinPath(dir, path), text)
        for (path in old) if (path !in files && !path.contains("..")) fs.delete(joinPath(dir, path))
        write(joinPath(dir, MANIFEST), files.keys.joinToString("\n"))
        write(projectFile(mod.id), mod.toJson())
    }

    fun delete(id: String) = fs.delete(folder(id))

    private fun write(path: String, text: String) {
        val parent = path.substringBeforeLast('/')
        if (parent.isNotEmpty()) fs.mkdirs(parent)
        val tmp = "$path.tmp"
        fs.delete(tmp)
        val w = fs.openWrite(tmp)
        try {
            val bytes = text.encodeToByteArray()
            w.write(bytes, 0, bytes.size)
        } finally {
            w.close()
        }
        fs.delete(path)
        fs.rename(tmp, path)
    }

    companion object {
        const val PROJECT = "alpha3-luamod.json"
        /** The generated files of the last save, so the next one can remove those it no longer makes. */
        const val MANIFEST = ".alpha3-luamod-files"
    }
}
