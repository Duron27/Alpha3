package org.openmw.utils

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import org.openmw.BuildConfig
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.ui.controls.UIStateManager.gameList
import org.alpha3.launcher.ui.controls.UIStateManager.userUI
import org.alpha3.launcher.ui.view.addCustomLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ManageAssets(private val context: Context) {

    fun copy(src: String, dst: String, overwrite: Boolean = false) {
        val assetManager = context.assets
        val assets = assetManager.list(src) ?: return

        if (assets.isEmpty()) {
            copyFile(src, dst, overwrite)
        } else {
            File(dst).apply { mkdirs() }
            assets.forEach { asset ->
                copy("$src/$asset", "$dst/$asset", overwrite)
            }
        }
    }

    private fun copyFile(src: String, dst: String, overwrite: Boolean = false) {
        val destinationFile = File(dst)
        if (destinationFile.exists()) {
            if (!overwrite) {
                Log.d("ManageAssets", "File $dst already exists, skipping copy.")
                addCustomLog(
                    "ManageAssets, File $dst already exists, skipping copy.",
                    textSize = 10,
                    textColor = Color.White
                )
                return
            }

            // Smart check: Compare sizes
            try {
                context.assets.open(src).use { input ->
                    val assetSize = input.available().toLong()
                    if (destinationFile.length() == assetSize) {
                        Log.d("ManageAssets", "File $dst is identical (size match), skipping.")
                        return
                    }
                }
            } catch (_: IOException) {
                // Fallback to copy if size check fails
            }
        }
        try {
            context.assets.open(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("ManageAssets", "Copied file from $src to $dst")
            addCustomLog(
                "ManageAssets Copied file from $src to $dst",
                textSize = 10,
                textColor = Color.White
            )
        } catch (e: IOException) {
            Log.e("ManageAssets", "Error copying file from $src to $dst", e)
        }
    }
}

class UserManageAssets(val context: Context) {
    /** Where the launcher's shared assets (UI layouts, settings template, global config) are: the fork's folder, or the stock engine's when the build has no fork. */
    private val shared = if (BuildConfig.OPENMW_FORK) "libopenmw" else "libopenmw-upstream"
    val assetCopier = ManageAssets(context)

    fun deleteRecursive(fileOrDirectory: File) {
        if (fileOrDirectory.isDirectory) {
            fileOrDirectory.listFiles()?.forEach { deleteRecursive(it) }
        }
        fileOrDirectory.delete()
    }

    fun resetUserConfig() {
        val settingsFile = File(OpenMWPaths.SETTINGS_FILE)
        if (settingsFile.exists()) {
            Log.d("ManageAssets", "Deleting existing file: ${OpenMWPaths.SETTINGS_FILE}")
            settingsFile.delete()
        }
        assetCopier.copy("$shared/openmw/settings.fallback.cfg", OpenMWPaths.SETTINGS_FILE)
    }

    private fun copyIfNotExists(source: String, destination: String) {
        if (!File(destination).exists()) {
            Log.d("ManageAssets", "Copying $source to $destination")
            assetCopier.copy(source, destination)
        }
    }

    private fun ensureDirectoryExists(directory: String) {
        val dir = File(directory)
        if (!dir.isDirectory) {
            dir.mkdirs()
            Log.d("ManageAssets", "Created directory at $directory")
        }
    }

    fun resetUserResources() {
        if (!BuildConfig.OPENMW_FORK) return  // the stock engine's resources are copied by UpstreamEngine.prepare
        val resourcesDir = File(OpenMWPaths.USER_RESOURCES)
        if (resourcesDir.exists()) {
            Log.d("ManageAssets", "Deleting existing user resources: ${OpenMWPaths.USER_RESOURCES}")
            resourcesDir.deleteRecursively()
        }
        assetCopier.copy("libopenmw/resources", OpenMWPaths.USER_RESOURCES, overwrite = true)
    }

    fun onFirstLaunch() {

        assetCopier.copy("$shared/openmw", OpenMWPaths.GLOBAL_CONFIG)
        // Setup storage directories
        listOf("OpenMW/Override", "OpenMW/CACHE", "OpenMW/Mods", "alpha3_generated", "config", "dethrace", "uqm", "uqm/content").forEach { dir ->
            ensureDirectoryExists("${OpenMWPaths.USER_FILE_STORAGE}/$dir")
        }
        if (!File("${OpenMWPaths.USER_FILE_STORAGE}/.nomedia").exists()) {
            File("${OpenMWPaths.USER_FILE_STORAGE}/.nomedia").writeText("nomedia")
        }
        if (!File(OpenMWPaths.USER_OPENMW_CFG).exists()) {
            File(OpenMWPaths.USER_OPENMW_CFG).writeText("# This is the user openmw.cfg. Feel free to modify it as you wish.\n")
        }
        gameList.forEach { game ->
            val uiDir = "${OpenMWPaths.USER_FILE_STORAGE}/$game/ui"
            val uiCfgFile = File("$uiDir/button_configs.json")

            if (!uiCfgFile.exists()) {
                assetCopier.copy("$shared/ui", uiDir)
            }
        }
        copyIfNotExists("$shared/openmw/settings.fallback.cfg", OpenMWPaths.SETTINGS_FILE)
        UserManageAssets(context).installUQMResourceFiles()
    }

    fun resourcePrepare() {
        // Stock-only build: the fork's resources and defaults.bin are not in the APK; UpstreamEngine.prepare copies the engine's own
        if (!BuildConfig.OPENMW_FORK) return
        val stampFile = File(OpenMWPaths.VERSION_STAMP)
        val currentStamp = if (stampFile.exists()) stampFile.readText().trim() else ""
        val targetStamp = BuildConfig.RANDOMIZER.toString()

        val resourcesDir = File(OpenMWPaths.USER_RESOURCES)
        val defaultsBin = File(OpenMWPaths.DEFAULTS_BIN)

        if (currentStamp == targetStamp && resourcesDir.exists() && defaultsBin.exists()) {
            Log.d("ManageAssets", "Version match and resources exist, skipping resource update.")
            return
        }

        if (currentStamp != targetStamp) {
            Log.d("ManageAssets", "Version mismatch: current='$currentStamp', target='$targetStamp'. Updating resources...")
        } else {
            Log.d("ManageAssets", "Resources missing, re-copying...")
        }

        // Copy the resources with overwrite enabled
        Log.d("ManageAssets", "Smart copying resources from libopenmw/resources to ${OpenMWPaths.USER_RESOURCES}")
        assetCopier.copy("libopenmw/openmw/defaults.bin", OpenMWPaths.DEFAULTS_BIN, overwrite = true)
        assetCopier.copy("libopenmw/resources", OpenMWPaths.USER_RESOURCES, overwrite = true)

        // Write the new stamp after successful copy
        try {
            stampFile.writeText(targetStamp)
            Log.d("ManageAssets", "Successfully updated resources and stamp: $targetStamp")
        } catch (e: Exception) {
            Log.e("ManageAssets", "Failed to write version stamp", e)
        }
    }

    fun resetUI() {
        val uiDir = File(userUI)
        uiDir.deleteRecursively()
        if (uiDir.exists()) {
            uiDir.delete()
        }
        ensureDirectoryExists(userUI)
        assetCopier.copy("$shared/ui", userUI)
    }

    fun installUQMResourceFiles() {
        val contentDir = uqmContentRoot()
        if (!contentDir.exists()) {
            contentDir.mkdirs()
        }

        val versionFile = File(contentDir, "version")

        // Write EXACT content
        versionFile.writeText(
        $$"""
        0.8.3

        $Format:%ad$
        """.trimIndent()
        )
    }
}
