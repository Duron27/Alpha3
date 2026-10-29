package org.alpha3.launcher.tools

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.alpha3.launcher.io.systemFileSystem

/**
 * End-to-end replacement for `openmw-iniimporter --game-files <Game>/Morrowind.ini <openmw.cfg>`:
 * the file IO, Windows-1252 decoding and ESM header reading that [IniImporter] leaves to the caller.
 */
object IniImportRunner {

    /**
     * @param gameFilesPath folder containing Morrowind.ini and "Data Files".
     * @param cfgPath openmw.cfg to merge into (read, then rewritten in place).
     * @param importContent add the ini's plugin list as `content=` (`--game-files`).
     * @param importArchives add the ini's archives as `fallback-archive=`.
     * @param keepComments the C++ tool drops `#` comment lines when it rewrites the file. With this set they are
     *   appended at the end. (`;content=Mod.esp` markers of disabled plugins survive either way.)
     * @param keepExistingContent the C++ tool replaces every `content=` line with the ini's plugin list. With
     *   this set, plugins already in the config that the ini does not list (mods the user enabled) are kept,
     *   after the ini's plugins in their old order.
     * @return 0 on success, -3 if Morrowind.ini is missing.
     */
    fun run(
        gameFilesPath: String,
        cfgPath: String,
        encoding: String = "win1252",
        keepExistingContent: Boolean = false,
        importContent: Boolean = true,
        importArchives: Boolean = true,
        keepComments: Boolean = false,
        fs: FileSystem = systemFileSystem,
        log: (String) -> Unit = {},
    ): Int {
        val dir = gameFilesPath.trimEnd('/')
        val iniPath = "$dir/Morrowind.ini".toPath()
        if (!fs.exists(iniPath)) {
            log("ini file does not exist")
            return -3
        }
        val cfgFile = cfgPath.toPath()

        val importer = IniImporter()
        importer.log = log

        val oldText = if (fs.exists(cfgFile)) decode(fs.read(cfgFile) { readByteArray() }, "utf-8") else null
        val oldLines = oldText?.lines().orEmpty()
        val cfg = if (oldText != null) importer.loadCfg(oldText)
        else linkedMapOf<String, MutableList<String>>().also { log("cfg file does not exist") }

        // A stored encoding wins over the default, like the C++ tool.
        val usedEncoding = cfg["encoding"]?.lastOrNull()?.takeIf { it.isNotEmpty() && encoding == "win1252" } ?: encoding
        cfg["encoding"] = mutableListOf(usedEncoding)

        val ini = importer.loadIni(decode(fs.read(iniPath) { readByteArray() }, usedEncoding))
        // Bitmap fonts are not imported unless asked for
        ini.remove("Fonts:Font 0")
        ini.remove("Fonts:Font 1")
        ini.remove("Fonts:Font 2")

        val oldContent = cfg["content"]?.toList().orEmpty()

        importer.merge(cfg, ini)
        importer.mergeFallback(cfg, ini)
        if (importContent) {
            importer.importGameFiles(
                cfg,
                ini,
                dataPaths = listOf("$dir/Data Files"),
                lastWriteTime = { path -> lastWriteSeconds(path, fs) },
                readDependencies = { path -> readDependencies(path, usedEncoding, fs) },
            )
        }
        if (keepExistingContent) {
            val content = cfg.getOrPut("content") { mutableListOf() }
            for (plugin in oldContent) {
                if (content.none { it.equals(plugin, ignoreCase = true) }) content.add(plugin)
            }
        }
        if (importArchives) importer.importArchives(cfg, ini)

        var text = IniImporter.writeToString(cfg)
        if (keepComments) {
            // lines starting with ';' are kept by the importer as ordinary entries, only '#' lines are dropped
            val comments = oldLines.filter { it.trimStart().startsWith("#") }
            if (comments.isNotEmpty()) text += comments.joinToString("\n", postfix = "\n")
        }
        fs.write(cfgFile) { writeUtf8(text) }
        return 0
    }

    internal fun lastWriteSeconds(path: String, fs: FileSystem): Long? =
        fs.metadataOrNull(path.toPath())?.lastModifiedAtMillis?.div(1000)

    /** Name and master list of a plugin from its TES3 header record. */
    internal fun readDependencies(path: String, encoding: String, fs: FileSystem): Pair<String, List<String>> {
        val name = path.substringAfterLast('/')
        val masters = mutableListOf<String>()
        fs.source(path.toPath()).buffer().use { src ->
            if (!src.request(16)) throw IllegalStateException("$path: not a Morrowind plugin")
            val tag = src.readByteArray(4).decodeToString()
            if (tag != "TES3") throw IllegalStateException("$path: not a Morrowind plugin")
            val size = src.readIntLe().toLong() and 0xFFFFFFFFL
            src.skip(8) // unknown + flags
            var read = 0L
            while (read + 8 <= size) {
                val sub = src.readByteArray(4).decodeToString()
                val subSize = src.readIntLe().toLong() and 0xFFFFFFFFL
                read += 8 + subSize
                if (sub == "MAST") {
                    val raw = src.readByteArray(subSize)
                    var end = raw.indexOf(0.toByte())
                    if (end < 0) end = raw.size
                    masters.add(decode(raw.copyOf(end), encoding))
                } else {
                    src.skip(subSize)
                }
            }
        }
        return name to masters
    }

    private val WIN1252_80 = charArrayOf(
        '€', '\u0081', '‚', 'ƒ', '„', '…', '†', '‡',
        'ˆ', '‰', 'Š', '‹', 'Œ', '\u008D', 'Ž', '\u008F',
        '\u0090', '‘', '’', '“', '”', '•', '–', '—',
        '˜', '™', 'š', '›', 'œ', '\u009D', 'ž', 'Ÿ',
    )

    // 0x80..0xBF of windows-1251
    private const val WIN1251_80 =
        "\u0402\u0403\u201A\u0453\u201E\u2026\u2020\u2021\u20AC\u2030\u0409\u2039\u040A\u040C\u040B\u040F" +
            "\u0452\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u0098\u2122\u0459\u203A\u045A\u045C\u045B\u045F" +
            "\u00A0\u040E\u045E\u0408\u00A4\u0490\u00A6\u00A7\u0401\u00A9\u0404\u00AB\u00AC\u00AD\u00AE\u0407" +
            "\u00B0\u00B1\u0406\u0456\u0491\u00B5\u00B6\u00B7\u0451\u2116\u0454\u00BB\u0458\u0405\u0455\u0457"

    /** Decodes [bytes] as UTF-8 or the Windows-125x codepage named by [encoding] (win1252 for the others). */
    internal fun decode(bytes: ByteArray, encoding: String): String {
        if (encoding.equals("utf-8", true) || encoding.equals("utf8", true)) {
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()) 3 else 0
            return bytes.decodeToString(start, bytes.size)
        }
        // win1250 is approximated by win1252 above 0x7F; its accented letters differ, keys and file names
        // in a stock Morrowind.ini are ASCII.
        val cyrillic = encoding.equals("win1251", true)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            sb.append(
                when {
                    c < 0x80 -> c.toChar()
                    cyrillic && c >= 0xC0 -> (0x0410 + c - 0xC0).toChar()
                    cyrillic -> WIN1251_80[c - 0x80]
                    c <= 0x9F -> WIN1252_80[c - 0x80]
                    else -> c.toChar()
                },
            )
        }
        return sb.toString()
    }
}
