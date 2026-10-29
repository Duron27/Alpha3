package org.alpha3.launcher.paths

// use these everywhere in commonMain
import org.alpha3.launcher.paths.OpenMWPaths
val cfgPath = OpenMWPaths.USER_OPENMW_CFG


expect object OpenMWPaths {
    val RANDOM_NUM: String

    val USER_FILE_STORAGE: String
    val SECOND_USER_FILE_STORAGE: String
    val DEFAULTS_BIN: String
    val OPENMW_CFG: String
    val SETTINGS_FILE: String
    val UMO_HELPER: String
    val LOGCAT_FILE: String
    val OPENMW_LOG: String
    val GLOBAL_CONFIG: String
    /** The base config folder (`Alpha3/config`): crash reports, symbols, the profile list. Never changes. */
    val USER_CONFIG: String
    /** Same as [USER_CONFIG]; named for the profile code, which builds the profile folders below it. */
    val BASE_CONFIG: String
    /** The config folder of the active profile (see [Profiles]): its openmw.cfg, settings.cfg and engine log. */
    val ACTIVE_CONFIG: String
    /** The engine's user-data folder for the active profile: saves, screenshots, navmesh.db. */
    val USER_DATA: String
    val USER_RESOURCES: String
    val USER_SAVES: String
    val USER_GENERATED: String
    val USER_OPENMW_CFG: String
    val VERSION_STAMP: String
    val CRASH_FILE: String
    val INTERNAL_CRASH_FILE: String
    val CACHE_DIR: String
    val LIBRARY_ROOT: String

    fun setupResources()
}
