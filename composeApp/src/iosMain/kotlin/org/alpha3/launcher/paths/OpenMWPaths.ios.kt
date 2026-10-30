@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher.paths

import org.alpha3.launcher.io.File
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.runBlocking
import androidx.datastore.preferences.core.edit
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore
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

    actual val BASE_CONFIG: String
        get() = USER_CONFIG

    // As on Android: the default profile keeps everything where it always was; any other profile is a folder below the
    // config folder that is also its user-data folder.
    actual val ACTIVE_CONFIG: String
        get() = Profiles.folder

    actual val USER_DATA: String
        get() = if (Profiles.isDefault) USER_FILE_STORAGE else Profiles.folder

    actual val USER_RESOURCES: String by lazy {
        val p = "$USER_FILE_STORAGE/resources"
        ensureDir(p)
        p
    }

    actual val USER_SAVES: String
        get() = "$USER_DATA/saves".also { ensureDir(it) }

    actual val USER_GENERATED: String
        get() = "$USER_DATA/alpha3_generated".also { ensureDir(it) }

    actual val USER_OPENMW_CFG: String
        get() = "$ACTIVE_CONFIG/openmw.cfg"

    actual val SETTINGS_FILE: String
        get() = "$ACTIVE_CONFIG/settings.cfg"

    actual val LOGCAT_FILE: String
        get() = "$ACTIVE_CONFIG/openmw_logcat.txt"

    actual val OPENMW_LOG: String
        get() = "$ACTIVE_CONFIG/openmw.log"

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
    private fun setupConfigs(engineChanged: Boolean) {
        val fm = NSFileManager.defaultManager
        // The global config's data-local points at OpenMW/Override; the engine warns when it is missing
        // ("No such dir"). Android creates the same folders on its first launch.
        listOf("OpenMW/Override", "OpenMW/Mods").forEach { ensureDir("$USER_FILE_STORAGE/$it") }
        val assets = "${NSBundle.mainBundle.resourcePath}/OpenMWAssets"
        // settings.cfg is the user's; the other two belong to the engine and follow the bundled build.
        for ((name, target, engineOwned) in listOf(
            Triple("settings.cfg", SETTINGS_FILE, false),
            Triple("defaults.bin", DEFAULTS_BIN, true),
            Triple("gamecontrollerdb.txt", "$GLOBAL_CONFIG/gamecontrollerdb.txt", true),
        )) {
            if (!fm.fileExistsAtPath("$assets/$name")) continue
            if (engineOwned && engineChanged) fm.removeItemAtPath(target, null)
            if (!fm.fileExistsAtPath(target)) {
                if (!fm.copyItemAtPath("$assets/$name", target, null)) println("Failed to copy $name")
            }
        }
        if ((engineChanged || !fm.fileExistsAtPath(OPENMW_CFG)) && fm.fileExistsAtPath("$assets/openmw.cfg")) {
            // The settings page's charset is the only line the launcher edits in the global config; keep it.
            val encoding = if (fm.fileExistsAtPath(OPENMW_CFG)) File(OPENMW_CFG).readLines().find { it.startsWith("encoding=") } else null
            // The template points at its own folder; the copied resources live in the container instead.
            var text = File("$assets/openmw.cfg").readText()
                .replace("\nresources=resources", "\nresources=$USER_RESOURCES")
                .replace("\ndata=resources/vfs-mw", "\ndata=$USER_RESOURCES/vfs-mw")
            if (encoding != null) text = text.lines().filterNot { it.startsWith("encoding=") }.joinToString("\n") + "\n$encoding\n"
            File(OPENMW_CFG).writeText(text)
        }
        // Like Android's first launch: the mod list reads the user config before any game is selected.
        // Only a comment goes in; the game's paths and plugins are added when a Morrowind folder is selected.
        if (!fm.fileExistsAtPath(USER_OPENMW_CFG)) {
            File(USER_OPENMW_CFG).writeText("# This is the user openmw.cfg. Feel free to modify it as you wish.\n")
        }
    }

    /**
     * Identifies the bundled engine: the app version and build plus the engine binary's timestamp, so a new
     * TestFlight build and a new local build both count as a change.
     */
    private fun bundleStamp(): String {
        val info = NSBundle.mainBundle.infoDictionary
        val engine = "${NSBundle.mainBundle.privateFrameworksPath}/libopenmw.framework/libopenmw"
        val modified = (NSFileManager.defaultManager.attributesOfItemAtPath(engine, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970
        return "${info?.get("CFBundleShortVersionString")}-${info?.get("CFBundleVersion")}-$modified"
    }

    /**
     * iOS moves the app to a new data container (a new UUID in its path) when the app is reinstalled or updated,
     * so absolute paths saved earlier point nowhere. Point them at the current container.
     */
    private fun migrateContainerPaths() {
        val oldContainer = Regex("/Data/Application/[0-9A-Fa-f-]{36}(?=/)")
        val home = NSHomeDirectory()
        val current = oldContainer.find("$home/")?.value ?: return
        fun fix(text: String) = oldContainer.replace(text, current)
        // Every profile's openmw.cfg, not just the active one, and the mod lists' generated cfgs a profile is made from
        val listCfgs = File("$USER_FILE_STORAGE/OpenMW").listFiles { it.isFile && it.name.endsWith(".cfg") }.orEmpty().map { it.absolutePath }
        val cfgs = listOf(OPENMW_CFG, "$USER_CONFIG/openmw.cfg") + Profiles.list().map { "${Profiles.folderOf(it)}/openmw.cfg" } + listCfgs
        for (cfg in cfgs) {
            if (!NSFileManager.defaultManager.fileExistsAtPath(cfg)) continue
            val text = File(cfg).readText()
            val fixed = fix(text)
            if (fixed != text) { File(cfg).writeText(fixed); println("Moved $cfg to the current app container") }
        }
        runBlocking {
            dataStore.edit { prefs ->
                val saved = prefs[GameFilesPreferences.GAME_FILES_URI_KEY] ?: return@edit
                val fixed = fix(saved)
                if (fixed != saved) prefs[GameFilesPreferences.GAME_FILES_URI_KEY] = fixed
            }
        }
    }

    /**
     * The engine's cache (converted textures, shader programs; support/src/settings.cpp). It lives in Application
     * Support because iOS empties Library/Caches when storage runs low, and with it hours of texture conversion; it is
     * kept out of backups, since it can be made again.
     */
    val ENGINE_CACHE: String
        get() = "$USER_FILE_STORAGE/cache"

    /** Moves the cache earlier builds kept in Library/Caches/openmw, and keeps it out of iCloud and computer backups. */
    private fun setupEngineCache() {
        val fm = NSFileManager.defaultManager
        val old = "${cachesDir()}/openmw"
        if (fm.fileExistsAtPath(old) && !fm.fileExistsAtPath(ENGINE_CACHE)) fm.moveItemAtPath(old, ENGINE_CACHE, null)
        ensureDir(ENGINE_CACHE)
        NSURL.fileURLWithPath(ENGINE_CACHE).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    }

    actual fun setupResources() {
        val stamp = bundleStamp()
        val engineChanged = File(VERSION_STAMP).let { !it.exists() || it.readText() != stamp }
        setupConfigs(engineChanged)
        setupEngineCache()
        migrateContainerPaths()
        val fm = NSFileManager.defaultManager
        val bundlePath = "${NSBundle.mainBundle.resourcePath}/OpenMWAssets/resources"
        val destPath = USER_RESOURCES

        if (!fm.fileExistsAtPath(bundlePath)) {
            println("Bundle resources not found at $bundlePath")
            return
        }

        val shadersDest = "$destPath/shaders"
        if (engineChanged || !fm.fileExistsAtPath(shadersDest)) {
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
        File(VERSION_STAMP).writeText(stamp)
    }
}


