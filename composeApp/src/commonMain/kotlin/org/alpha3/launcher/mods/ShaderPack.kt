package org.alpha3.launcher.mods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.mods.downloader.Archives
import org.alpha3.launcher.mods.downloader.NexusFiles
import org.alpha3.launcher.mods.downloader.modCache
import org.alpha3.launcher.mods.downloader.modStorage
import org.alpha3.launcher.paths.OpenMWPaths

/** What an install is doing, for the settings row. */
sealed interface ShaderStep {
    data class Waiting(val text: String) : ShaderStep
    data class Downloading(val bytes: Long, val total: Long) : ShaderStep
    data class Unpacking(val file: String) : ShaderStep
}

/**
 * A post-processing shader pack for OpenMW, installed as a data folder: its archive is fetched, unpacked to
 * OpenMW/Mods/<folderName> (an update replaces the old one) and that folder is added to the active profile's
 * openmw.cfg. The shaders are then picked in the game's F2 window.
 */
abstract class ShaderPack(private val folderName: String) {
    abstract val title: String
    abstract val subtitle: String
    /** Said after a successful install, after where it went. */
    open val hint: String = "Pick the shaders in the game's F2 window."

    val folder: String get() = "$modStorage/Mods/$folderName"

    fun isInstalled(): Boolean = hasShaders(File(folder))

    // Shaders live in shaders/ (OpenMW's VFS ignores case, and Nexus packs often say Shaders/)
    private fun hasShaders(dir: File) =
        dir.listFiles()?.any { it.path.substringAfterLast('/').equals("shaders", ignoreCase = true) } == true

    /** The archive's address, once known (asking Nexus for it, for example). */
    protected abstract suspend fun archiveUrl(onStep: (ShaderStep) -> Unit): String

    /** Downloads, unpacks and enables the newest version. Throws on failure. */
    suspend fun install(onStep: (ShaderStep) -> Unit) = withContext(Dispatchers.IO) {
        val url = archiveUrl(onStep)
        File(modCache).mkdirs()
        val archive = File("$modCache/$folderName.download")
        archive.delete()
        try {
            download(url, archive, onStep)
            val staging = File("$folder.new")
            staging.deleteRecursively()
            unpack(archive.path, staging.path, onStep)
            if (!hasShaders(staging)) {
                staging.deleteRecursively()
                throw IOException("The download has no shaders folder")
            }
            File(folder).deleteRecursively()
            if (!staging.renameTo(File(folder))) throw IOException("Could not move the shaders into $folder")
            addDataFolder(folder)
        } finally {
            archive.delete()
        }
    }

    private suspend fun download(url: String, file: File, onStep: (ShaderStep) -> Unit) =
        NexusFiles.download(url, file) { done, total -> onStep(ShaderStep.Downloading(done, total)) }

    // An archive that holds everything in one top folder (GitLab's zips, most Nexus archives) gives that folder's
    // contents to the data folder; any other gives its own top level
    private fun unpack(archive: String, target: String, onStep: (ShaderStep) -> Unit) {
        val items = Archives.list(archive)
        val strip = items.map { it.path.replace('\\', '/').trimStart('/').substringBefore('/') }.toSet().size == 1 &&
            items.any { !it.isFolder && it.path.replace('\\', '/').trimStart('/').contains('/') }
        val errors = ArrayList<String>()
        Archives.extract(
            archive,
            target = { item ->
                val path = item.path.replace('\\', '/').trimStart('/')
                val inside = if (strip) path.substringAfter('/', "") else path
                when {
                    item.isFolder || inside.isEmpty() -> null
                    inside.split('/').any { it == ".." } -> null
                    else -> {
                        val out = "$target/$inside"
                        File(out.substringBeforeLast('/')).mkdirs()
                        out
                    }
                }
            },
            onFile = { onStep(ShaderStep.Unpacking(it.path.substringAfterLast('/').substringAfterLast('\\'))) },
            onError = { errors.add(it) },
        )
        if (errors.isNotEmpty()) throw IOException("Unpacking failed: ${errors.first()}")
    }

    /** Adds `data="<dir>"` after the last data line of the active profile's openmw.cfg, unless it is there already. */
    private fun addDataFolder(dir: String) {
        val cfg = File(OpenMWPaths.USER_OPENMW_CFG)
        val lines = if (cfg.exists()) cfg.readLines().dropLastWhile { it.isEmpty() }.toMutableList() else mutableListOf()
        val same = { line: String ->
            val t = line.trim()
            t.startsWith("data=") && t.removePrefix("data=").trim().removeSurrounding("\"").trimEnd('/') == dir.trimEnd('/')
        }
        if (lines.any(same)) return
        val line = "data=\"$dir\""
        val lastData = lines.indexOfLast { it.trim().startsWith("data=") }
        if (lastData >= 0) lines.add(lastData + 1, line) else lines.add(line)
        cfg.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

/**
 * vtastek's shaders (https://gitlab.com/vtastek/omwfx-shaders): SSR, wetworld (dynamic puddles), SSAO HQ, motion
 * blur, bloom, godrays and more. The repository is itself a data folder; GitLab serves its newest state as a zip.
 */
object OmwfxShaders : ShaderPack("omwfx-shaders") {
    override val title = "Community shaders (omwfx-shaders)"
    override val subtitle = "vtastek's post-processing shaders: SSR, wet ground puddles, SSAO, bloom, godrays and more. " +
        "Downloads the newest version from GitLab."
    override val hint = "Pick the shaders in the game's F2 window (for puddles and reflections: wetworld, then SSR)."

    override suspend fun archiveUrl(onStep: (ShaderStep) -> Unit) =
        "https://gitlab.com/vtastek/omwfx-shaders/-/archive/main/omwfx-shaders-main.zip"
}

/**
 * Rafael's Shader Pack (https://www.nexusmods.com/morrowind/mods/53667): VAIO, HBAO, SMAA, godrays, wetworld and
 * more. The newest MAIN file is asked of Nexus with the mod downloader's API key; a premium account gets its link
 * straight away, a free one through the file's page (Slow download hands an nxm:// link back to the app).
 */
object RafaelShaderPack : ShaderPack("RafaelsShaderPack") {
    private const val MOD_ID = "53667"

    override val title = "Rafael's Shader Pack (Nexus)"
    override val subtitle = "VAIO, HBAO, SMAA, godrays, wetworld and more. Downloads the newest version from Nexus " +
        "with the mod downloader's API key (free accounts: press Slow download on the page that opens)."

    override suspend fun archiveUrl(onStep: (ShaderStep) -> Unit): String {
        onStep(ShaderStep.Waiting("Asking Nexus for the newest version"))
        val file = NexusFiles.newest(MOD_ID)
        return NexusFiles.downloadLink(MOD_ID, file.fileId) { onStep(ShaderStep.Waiting(it)) }
    }
}
