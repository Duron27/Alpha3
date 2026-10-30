package org.alpha3.launcher.mods.downloader

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.tools.openmw.disableMissingConfigEntries
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.Log
import org.alpha3.launcher.utils.Platform

fun saveConfigToFile(fileName: String, content: String): File {
    val file = File("$modStorage/", fileName)
    file.writeText(content)
    return file
}

suspend fun fetchJsonFromUrl(url: String): String = ModHttp.getText(url, userAgent = null)

data class OpenMWConfig(
    val fallbackValues: String,
    val dataPaths: List<String>,
    val fallbackArchives: List<String>,
    val contentFiles: List<String>,
    val groundcoverFiles: String
)

/** The site's cfg generator answer, with its Windows paths moved to this device's mod folder. */
fun parseOpenMWConfig(jsonString: String): OpenMWConfig {
    val openmwCfg = Json.parseToJsonElement(jsonString).jsonObject["openmw_cfg"] as? JsonObject
        ?: throw IllegalArgumentException("No openmw_cfg in the cfg generator's answer")

    fun transformPath(path: String): String = path
        .replace("C:\\games\\OpenMWMods\\", "$modStorage/Mods/")
        .replace("\\", "/")

    fun string(name: String): String = openmwCfg[name]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("No \"$name\" in the cfg generator's answer")

    fun lines(name: String) = string(name).split("\n").filter { it.isNotBlank() }.map { transformPath(it) }

    return OpenMWConfig(
        fallbackValues = transformPath(string("fallback values")),
        dataPaths = lines("data paths"),
        fallbackArchives = lines("fallback archives"),
        contentFiles = lines("content files"),
        groundcoverFiles = transformPath(string("groundcover files"))
    )
}

suspend fun formatOpenMWConfig(config: OpenMWConfig): String {
    val gameFilesUri = GameFilesPreferences.getNullablePreference(GameFilesPreferences.GAME_FILES_URI_KEY).first()
    val builder = StringBuilder()

    builder.append(config.fallbackValues).append("\n\n")

    builder.append("## Data Paths\n")
    builder.append("data=$gameFilesUri/Data Files\n")
    config.dataPaths.forEach { builder.append("$it\n") }
    builder.append("\n")

    builder.append("## BSAS\n")
    config.fallbackArchives.forEach { builder.append("$it\n") }
    builder.append("\n")

    builder.append("## Plugins\n")
    config.contentFiles
        .filter {
            it !in listOf(
                // What the site's generated config lists for the desktop tools; Alpha3 writes its own versions.
                "content=S3LightFixes.omwaddon",
                "content=delta-merged.omwaddon",
                "content=groundcover.omwaddon",
                "content=deleted_groundcover.omwaddon"
            )
        }
        .forEach { content -> builder.append(if (content.startsWith("content=")) content else "content=$content").append("\n") }

    return builder.toString()
}

/**
 * Once a run has finished: comments out the lines of the openmw.cfg files at [paths] that point at folders,
 * plugins or archives that do not exist (a failed download, a tool that did not run), so the game still starts.
 */
fun disableMissingConfigEntries(vararg paths: String?) {
    val fs = Platform.features.toolFs ?: return
    val platform = Platform.features.toolPlatform ?: return
    for (path in paths.filterNotNull().distinct()) {
        try {
            if (!File(path).isFile) continue
            val check = disableMissingConfigEntries(fs, platform, path) { Log.d("ConfigCheck", it) }
            if (check.disabled.isNotEmpty()) {
                addCustomLog("Disabled ${check.disabled.size} openmw.cfg entries with missing files in ${File(path).name}", textSize = 10, textColor = Color.Yellow)
            }
        } catch (e: Exception) {
            Log.e("ConfigCheck", "Could not check $path: ${e.message}")
        }
    }
}

/** What [installModListProfile] did, for the "mod list installed" popup. */
class ProfileInstall(
    val modList: String,
    val profile: String,
    val folder: String,
    /** True when settings.cfg was copied from the default profile; false when the profile already had its own. */
    val settingsCopied: Boolean,
    /** True when the list's cfg became the profile's openmw.cfg. */
    val cfgInstalled: Boolean,
    /** Entries of that openmw.cfg that were switched off because their files are missing. */
    val disabledEntries: Int,
    val problem: String?,
)

/**
 * Gives a finished mod list a profile of its own: the profile folder `Alpha3/config/<list>` gets settings.cfg from the
 * default profile (kept if the profile already has one) and the list's generated cfg, `OpenMW/<list>.cfg`, as its
 * openmw.cfg. The cfg replaces the file instead of being appended, because it is complete: the game's data folder,
 * the fallback values, the archives, every mod folder and every plugin, expansions included. Appending it to the
 * default's lines would only list the same plugins twice. The profile is not switched to here; the popup offers it.
 */
fun installModListProfile(modList: String, generatedCfg: String?): ProfileInstall {
    val name = Profiles.sanitize(modList)
    val folder = Profiles.folderOf(name)
    return try {
        val hadSettings = File(folder, "settings.cfg").exists()
        Profiles.create(name)
        // create() copies settings.cfg from the default profile when the folder is new; cover a folder that existed without one
        val settings = File(folder, "settings.cfg")
        if (!settings.exists()) File(OpenMWPaths.BASE_CONFIG, "settings.cfg").takeIf { it.isFile }?.copyTo(settings)
        val settingsCopied = !hadSettings && settings.exists()
        val source = File(generatedCfg ?: "$modStorage/$modList.cfg")
        if (!source.isFile) {
            return ProfileInstall(modList, name, folder, settingsCopied, false, 0, "The config for $modList was not downloaded, so the profile's openmw.cfg is still a copy of the default one.")
        }
        val target = File(folder, "openmw.cfg")
        source.copyTo(target, overwrite = true)
        val fs = Platform.features.toolFs
        val platform = Platform.features.toolPlatform
        val disabled = if (fs != null && platform != null) {
            disableMissingConfigEntries(fs, platform, target.absolutePath) { Log.d("ConfigCheck", it) }.disabled.size
        } else 0
        ProfileInstall(modList, name, folder, settingsCopied, true, disabled, null)
    } catch (e: Exception) {
        Log.e("ProfileInstall", "Could not install the profile for $modList", e)
        ProfileInstall(modList, name, folder, false, false, 0, "Could not set up the profile: ${e.message}")
    }
}
