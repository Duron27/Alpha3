package org.alpha3.launcher.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.alpha3.launcher.paths.OpenMWPaths

/** Single app wide preference store, shared by Android and iOS. */
val dataStore: DataStore<Preferences> by lazy {
    PreferenceDataStoreFactory.createWithPath(
        produceFile = { "${OpenMWPaths.USER_FILE_STORAGE}/game_files_prefs.preferences_pb".toPath() }
    )
}

object GameFilesPreferences {
    val GAME_FILES_URI_KEY = stringPreferencesKey("game_files_uri")
    val NEXUS_API_KEY = stringPreferencesKey("nexus_api_key")
    val IS_NEXUS_PREMIUM = booleanPreferencesKey("nexus_is_premium")
    val IS_NEXUS_VALIDATED = booleanPreferencesKey("nexus_is_validated")
    val UI_HIDDEN_STATE_KEY = stringPreferencesKey("ui_hidden_state")
    val VIBRATION_STATE_KEY = stringPreferencesKey("vibration_state")
    val MATCH_ICON_COLOR_KEY = stringPreferencesKey("match_icon_color")
    val RESOLUTION_X_KEY = intPreferencesKey("resolution_x")
    val RESOLUTION_Y_KEY = intPreferencesKey("resolution_y")
    val ICON_GLOW_KEY = booleanPreferencesKey("icon_glow")
    val ARG_LINE_KEY = stringPreferencesKey("argLine")
    val ENV_LINE_KEY = stringPreferencesKey("envLine")
    val USER_OPTIONS_KEY = stringPreferencesKey("userOptions")
    // The Launch options dialog: the environment variables and command line options switched on or off (see
    // org.alpha3.launcher.launch). ARG_LINE_KEY, ENV_LINE_KEY and USER_OPTIONS_KEY above are the old single lines, read once to migrate.
    val LAUNCH_ENV_KEY = stringPreferencesKey("launchEnv")
    val LAUNCH_ARGS_KEY = stringPreferencesKey("launchArgs")
    val LAUNCH_ARGS_CUSTOM_KEY = stringPreferencesKey("launchArgsCustom")
    val AUTO_MOUSE_MODE_KEY = stringPreferencesKey("autoMouseMode")
    val DISABLE_SWAPPY_KEY = booleanPreferencesKey("disableSwappy")
    val FRAME_GENERATION_KEY = booleanPreferencesKey("frameGeneration")
    /** Frames shown per rendered one with frame generation on: 2, 3 or 4 (OPENMW_FRAMEGEN_FRAMES is one less). */
    val FRAME_GENERATION_MULTIPLIER_KEY = intPreferencesKey("frameGenerationMultiplier")
    /** The stock OpenMW engine's OpenGL renderer: "gles" (the device's driver), "angle" (ANGLE, on Vulkan on Android and on Metal on iOS) or "zink" (Mesa Zink on Vulkan). */
    val GL_RENDERER_KEY = stringPreferencesKey("glRenderer")
    /** Zink frame pacing: the share of each frame's interval (50 to 100 percent) a frame may take before it gets a longer one. */
    val ZINK_FRAME_BUDGET_KEY = intPreferencesKey("zinkFrameBudget")
    val USE_FREEDRENO = booleanPreferencesKey("useFreedreno")
    val DRIVER_DEBUG_ENVS = stringPreferencesKey("driverDebugEnvs")
    /** Id of the GPU driver to load, [org.alpha3.launcher.utils.GpuDrivers.BUILTIN_ID] for the bundled one. */
    val SELECTED_GPU_DRIVER = stringPreferencesKey("selectedGpuDriver")
    val MOD_LISTS_SYNCED_AT = longPreferencesKey("modListsSyncedAt")
    val USE_VIRTUAL_KB = booleanPreferencesKey("virtualKB")
    /** Delete a mod's downloaded archive from CACHE once it is extracted and its list actions have run. */
    val DELETE_ARCHIVES_KEY = booleanPreferencesKey("deleteArchivesAfterInstall")
    val CODE_GROUP_KEY = stringPreferencesKey("whichGame")
    // Which OpenMW the "OpenMW" game starts: "fork" (clockwork, Vulkan) or "upstream" (stock OpenMW, OpenGL only)
    val OPENMW_ENGINE_KEY = stringPreferencesKey("openmwEngine")
    val BUTTON_GROUP_SWITCH_KEY = booleanPreferencesKey("button_group_switch")
    val SELECTED_ANIMATION_KEY = stringPreferencesKey("selected_animation")
    val SELECTED_MOUSE_KEYS = stringPreferencesKey("selected_mouse")
    val ALLOWED_TO_TEXT_EDITOR = stringPreferencesKey("allowed_to_edit")
    val NEW_FEATURE_ENABLED_KEY = booleanPreferencesKey("new_feature_enabled")
    /** Experimental: generate navmesh.db with the Kotlin port instead of the native openmw-navmeshtool. */
    val KOTLIN_NAVMESH_KEY = booleanPreferencesKey("kotlin_navmesh")
    val TRANSLATION_ENABLED_KEY = booleanPreferencesKey("translation_enabled")
    val BACKGROUND_ANIMATION_KEY = stringPreferencesKey("background_animation")
    val LANGUAGE_KEY = stringPreferencesKey("language")
    val SUPPORTED_LANGUAGES = stringPreferencesKey("supported_languages")
    val WHATS_NEW_KEY = booleanPreferencesKey("whats_new")
    val AUTO_RUN = booleanPreferencesKey("auto_run")
    val HIDE_SYSTEM_BARS = booleanPreferencesKey("hide_system_bars")
    val SCREEN_STAY_ON = booleanPreferencesKey("screen_stay_on")
    val IGNORE_CONTROLLER_KEY = booleanPreferencesKey("ignore_controller")
    val TUTORIAL_KEY = booleanPreferencesKey("tutorial_enable")
    val MENU_CORNER_KEY = intPreferencesKey("menu_corner")


    val KEYBOARD_BACKLIGHT = floatPreferencesKey("keyboard_backlight")
    val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
    val KEYBOARD_WIDTH = floatPreferencesKey("keyboard_width")
    val KEYBOARD_HEIGHT = floatPreferencesKey("keyboard_height")

    // Android 15 bug where game files not detected when launching
    val BYPASS_GAME_FILES_KEY = booleanPreferencesKey("bypass_game_files_check")

    // Developer Options
    val AVOID_RESOLUTION_INSERTION = booleanPreferencesKey("avoidInsertion")

    // Mouse Settings
    val OFFSET_X_MOUSE = floatPreferencesKey("offset_x_mouse")
    val OFFSET_Y_MOUSE = floatPreferencesKey("offset_y_mouse")
    val SENSITIVITY_MOUSE = floatPreferencesKey("sensitivity_mouse")

    val SENSITIVITY_RT = floatPreferencesKey("sensitivity_right_thumb")

    val BUTTON_SHAPE = stringPreferencesKey("button_shape")

    // Enable Virtual Left Thumbstick
    val VIRTUAL_LEFT_THUMBSTICK = booleanPreferencesKey("enable_virtual_left_thumbstick")
    val VIRTUAL_RIGHT_THUMBSTICK = booleanPreferencesKey("enable_virtual_right_thumbstick")

    fun <T> getPreference(key: Preferences.Key<T>, defaultValue: T): Flow<T> {
        return dataStore.data.map { preferences ->
            preferences[key] ?: defaultValue
        }
    }

    fun <T> getNullablePreference(key: Preferences.Key<T>): Flow<T?> {
        return dataStore.data.map { preferences ->
            preferences[key]
        }
    }

    suspend fun <T> setPreference(key: Preferences.Key<T>, value: T) {
        dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    suspend fun <T> setNullablePreference(key: Preferences.Key<T>, value: T?) {
        dataStore.edit { preferences ->
            if (value != null) {
                preferences[key] = value
            } else {
                preferences.remove(key)
            }
        }
    }
}
