@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher.paths

import org.alpha3.launcher.io.File
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import platform.Foundation.*

private fun documentsDir(): String =
    NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true
    ).first() as String

private fun appSupportDir(): String =
    NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true
    ).first() as String

private fun cachesDir(): String =
    NSSearchPathForDirectoriesInDomains(
        NSCachesDirectory,
        NSUserDomainMask,
        true
    ).first() as String

private fun libraryDir(): String =
    NSSearchPathForDirectoriesInDomains(
        NSLibraryDirectory,
        NSUserDomainMask,
        true
    ).first() as String

private fun ensureDir(path: String) {
    val fileManager = NSFileManager.defaultManager
    memScoped {
        fileManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
    }
}

actual object OpenMWPaths {
    actual val RANDOM_NUM: String = "Alpha-22556"

    private val baseStorage: String by lazy {
        val base = "${appSupportDir()}/Alpha3"
        ensureDir(base)
        base
    }

    actual val USER_FILE_STORAGE: String
        get() = baseStorage

    actual val SECOND_USER_FILE_STORAGE: String
        get() = documentsDir() // or same as USER_FILE_STORAGE if you prefer

    actual val USER_CONFIG: String by lazy {
        val p = "${libraryDir()}/Preferences/openmw"
        ensureDir(p)
        p
    }

    // Profiles are not used on iOS: the active profile is always the plain config folder
    actual val BASE_CONFIG: String
        get() = USER_CONFIG

    actual val ACTIVE_CONFIG: String
        get() = USER_CONFIG

    actual val USER_DATA: String
        get() = USER_FILE_STORAGE

    actual val USER_RESOURCES: String by lazy {
        val p = "$USER_FILE_STORAGE/resources"
        ensureDir(p)
        p
    }

    actual val USER_SAVES: String by lazy {
        val p = "$USER_FILE_STORAGE/saves"
        ensureDir(p)
        p
    }

    actual val USER_GENERATED: String by lazy {
        val p = "$USER_FILE_STORAGE/alpha3_generated"
        ensureDir(p)
        p
    }

    actual val USER_OPENMW_CFG: String
        get() = "$USER_CONFIG/openmw.cfg"

    actual val SETTINGS_FILE: String
        get() = "$USER_CONFIG/settings.cfg"

    actual val LOGCAT_FILE: String
        get() = "$USER_CONFIG/openmw_logcat.txt"

    actual val OPENMW_LOG: String
        get() = "$USER_CONFIG/openmw.log"

    actual val CRASH_FILE: String
        get() = "$USER_CONFIG/crash.log"

    actual val UMO_HELPER: String
        get() = "$USER_CONFIG/UMOhelper.sh"

    /** The app's own base config, like Android's `files/config`; the engine reads it as its "local" config. */
    actual val GLOBAL_CONFIG: String by lazy {
        val p = "$USER_FILE_STORAGE/config"
        ensureDir(p)
        p
    }

    actual val VERSION_STAMP: String
        get() = "$USER_FILE_STORAGE/stamp"

    actual val DEFAULTS_BIN: String
        get() = "$GLOBAL_CONFIG/defaults.bin"

    actual val INTERNAL_CRASH_FILE: String
        get() = "$USER_CONFIG/crash_internal.log"

    actual val OPENMW_CFG: String
        get() = "$GLOBAL_CONFIG/openmw.cfg"

    actual val CACHE_DIR: String by lazy {
        val p = "${cachesDir()}/Alpha3/OpenMW/CACHE"
        ensureDir(p)
        p
    }

    actual val LIBRARY_ROOT: String
        get() = libraryDir()

    /**
     * First run: the default configs live in the bundle next to `resources`. Copy the ones that are missing,
     * as Android does. The base `openmw.cfg` and `defaults.bin` go to the global config folder, `settings.cfg`
     * to the user's. The user `openmw.cfg` is created holding only a comment; it gets the game's paths and plugins once a
     * Morrowind folder is selected. Existing files are the user's and stay.
     */
    private fun setupConfigs() {
        val fm = NSFileManager.defaultManager
        // The global config's data-local points at OpenMW/Override; the engine warns when it is missing
        // ("No such dir"). Android creates the same folders on its first launch.
        listOf("OpenMW/Override", "OpenMW/Mods").forEach { ensureDir("$USER_FILE_STORAGE/$it") }
        val assets = "${NSBundle.mainBundle.resourcePath}/OpenMWAssets"
        for ((name, target) in listOf(
            "settings.cfg" to SETTINGS_FILE,
            "defaults.bin" to DEFAULTS_BIN,
            "gamecontrollerdb.txt" to "$GLOBAL_CONFIG/gamecontrollerdb.txt",
        )) {
            if (!fm.fileExistsAtPath(target) && fm.fileExistsAtPath("$assets/$name")) {
                if (!fm.copyItemAtPath("$assets/$name", target, null)) println("Failed to copy $name")
            }
        }
        if (!fm.fileExistsAtPath(OPENMW_CFG) && fm.fileExistsAtPath("$assets/openmw.cfg")) {
            // The template points at its own folder; the copied resources live in the container instead.
            val text = File("$assets/openmw.cfg").readText()
                .replace("\nresources=resources", "\nresources=$USER_RESOURCES")
                .replace("\ndata=resources/vfs-mw", "\ndata=$USER_RESOURCES/vfs-mw")
            File(OPENMW_CFG).writeText(text)
        }
        // Like Android's first launch: the mod list reads the user config before any game is selected.
        // Only a comment goes in; the game's paths and plugins are added when a Morrowind folder is selected.
        if (!fm.fileExistsAtPath(USER_OPENMW_CFG)) {
            File(USER_OPENMW_CFG).writeText("# This is the user openmw.cfg. Feel free to modify it as you wish.\n")
        }
    }

    actual fun setupResources() {
        setupConfigs()
        val fm = NSFileManager.defaultManager
        val bundlePath = "${NSBundle.mainBundle.resourcePath}/OpenMWAssets/resources"
        val destPath = USER_RESOURCES

        if (!fm.fileExistsAtPath(bundlePath)) {
            println("Bundle resources not found at $bundlePath")
            return
        }

        val shadersDest = "$destPath/shaders"
        if (!fm.fileExistsAtPath(shadersDest)) {
            println("Copying resources from bundle to $destPath...")
            
            // If destPath exists, remove it so we can copy the whole folder
            if (fm.fileExistsAtPath(destPath)) {
                fm.removeItemAtPath(destPath, null)
            }

            val result = fm.copyItemAtPath(bundlePath, destPath, null)
            if (!result) {
                println("Failed to copy resources (check paths or permissions)")
            } else {
                println("Resources copied successfully.")
            }
        } else {
            println("Resources already present in $destPath")
        }
    }
}


