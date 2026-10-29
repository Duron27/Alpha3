package org.openmw.modDownloader

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.tools.openmw.AndroidToolFs
import org.alpha3.launcher.tools.openmw.androidToolPlatform
import org.alpha3.launcher.tools.openmw.disableMissingConfigEntries
import org.alpha3.launcher.utils.GameFilesPreferences
import androidx.compose.ui.graphics.Color
import org.alpha3.launcher.ui.view.addCustomLog
import java.io.File
import java.io.FileOutputStream

fun saveConfigToFile(fileName: String, content: String): File {
    val file = File("${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/", fileName)
    FileOutputStream(file).use { output ->
        output.write(content.toByteArray())
    }
    return file
}

fun fetchJsonFromUrl(url: String): String {
    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    client.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
            return response.body.string()
        } else {
            throw Exception("Failed to fetch JSON: ${response.code}")
        }
    }
}

data class OpenMWConfig(
    val fallbackValues: String,
    val dataPaths: List<String>,
    val fallbackArchives: List<String>,
    val contentFiles: List<String>,
    val groundcoverFiles: String
)

fun parseOpenMWConfig(jsonString: String): OpenMWConfig {
    val jsonObject = JSONObject(jsonString)
    val openmwCfg = jsonObject.getJSONObject("openmw_cfg")

    // Helper function to transform paths
    fun transformPath(path: String): String {
        return path
            .replace("C:\\games\\OpenMWMods\\", "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/Mods/")
            .replace("\\", "/")
    }

    val fallbackValues = transformPath(openmwCfg.getString("fallback values"))
    val dataPaths = openmwCfg.getString("data paths")
        .split("\n")
        .filter { it.isNotBlank() }
        .map { transformPath(it) }
    val fallbackArchives = openmwCfg.getString("fallback archives")
        .split("\n")
        .filter { it.isNotBlank() }
        .map { transformPath(it) }
    val contentFiles = openmwCfg.getString("content files")
        .split("\n")
        .filter { it.isNotBlank() }
        .map { transformPath(it) }
    val groundcoverFiles = transformPath(openmwCfg.getString("groundcover files"))

    return OpenMWConfig(
        fallbackValues = fallbackValues,
        dataPaths = dataPaths,
        fallbackArchives = fallbackArchives,
        contentFiles = contentFiles,
        groundcoverFiles = groundcoverFiles
    )
}

suspend fun formatOpenMWConfig(context: Context, config: OpenMWConfig): String {
    val gameFilesUri = GameFilesPreferences.getNullablePreference(GameFilesPreferences.GAME_FILES_URI_KEY).first()
    val builder = StringBuilder()

    // Fallback Values
    builder.append(config.fallbackValues).append("\n\n")

    // Data Paths
    builder.append("## Data Paths\n")
    builder.append("data=$gameFilesUri/Data Files\n")
    config.dataPaths.forEach { path ->
        builder.append("$path\n")
    }
    builder.append("\n")

    // BSAs
    builder.append("## BSAS\n")
    config.fallbackArchives.forEach { archive ->
        builder.append("$archive\n")
    }
    builder.append("\n")

    // Plugins
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
        .forEach { content ->
            val contentLine = if (content.startsWith("content=")) {
                content
            } else {
                "content=$content"
            }

            builder.append("$contentLine\n")
        }

    return builder.toString()
}

/**
 * Once a run has finished: comments out the lines of the openmw.cfg files at [paths] that point at folders,
 * plugins or archives that do not exist (a failed download, a tool that did not run), so the game still starts.
 */
fun disableMissingConfigEntries(vararg paths: String?) {
    for (path in paths.filterNotNull().distinct()) {
        try {
            if (!File(path).isFile) continue
            val check = disableMissingConfigEntries(AndroidToolFs, androidToolPlatform(), path) { Log.d("ConfigCheck", it) }
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
        val source = File(generatedCfg ?: "${OpenMWPaths.USER_FILE_STORAGE}/OpenMW/$modList.cfg")
        if (!source.isFile) {
            return ProfileInstall(modList, name, folder, settingsCopied, false, 0, "The config for $modList was not downloaded, so the profile's openmw.cfg is still a copy of the default one.")
        }
        val target = File(folder, "openmw.cfg")
        source.copyTo(target, overwrite = true)
        val check = disableMissingConfigEntries(AndroidToolFs, androidToolPlatform(), target.absolutePath) { Log.d("ConfigCheck", it) }
        ProfileInstall(modList, name, folder, settingsCopied, true, check.disabled.size, null)
    } catch (e: Exception) {
        Log.e("ProfileInstall", "Could not install the profile for $modList", e)
        ProfileInstall(modList, name, folder, false, false, 0, "Could not set up the profile: ${e.message}")
    }
}
