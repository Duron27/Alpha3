package org.openmw.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.openmw.Constants
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private var dataStoreInstance: DataStore<Preferences>? = null
private val dataStoreLock = Any()

val Context.dataStore: DataStore<Preferences>
    get() = synchronized(dataStoreLock) {
        dataStoreInstance ?: PreferenceDataStoreFactory.create(
            produceFile = { File(Constants.USER_FILE_STORAGE, "game_files_prefs.preferences_pb") }
        ).also { dataStoreInstance = it }
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
    val AUTO_MOUSE_MODE_KEY = stringPreferencesKey("autoMouseMode")
    val AVOID_16_BITS_KEY = booleanPreferencesKey("avoid16bits")
    val USE_ANGLE = booleanPreferencesKey("enableANGLE")
    val USE_ZINK = booleanPreferencesKey("enableZINK")
    val USE_SPIRV = booleanPreferencesKey("enableSPIRV")
    val USE_VIRTUAL_KB = booleanPreferencesKey("virtualKB")
    val TEXTURE_SHRINKING_KEY = stringPreferencesKey("textureShrinking")
    val CODE_GROUP_KEY = stringPreferencesKey("whichGame")
    val BUTTON_GROUP_SWITCH_KEY = booleanPreferencesKey("button_group_switch")
    val SELECTED_ANIMATION_KEY = stringPreferencesKey("selected_animation")
    val SELECTED_MOUSE_KEYS = stringPreferencesKey("selected_mouse")
    val ALLOWED_TO_TEXT_EDITOR = stringPreferencesKey("allowed_to_edit")
    val NEW_FEATURE_ENABLED_KEY = booleanPreferencesKey("new_feature_enabled")
    val TRANSLATION_ENABLED_KEY = booleanPreferencesKey("translation_enabled")
    val BACKGROUND_ANIMATION_KEY = stringPreferencesKey("background_animation")
    val LANGUAGE_KEY = stringPreferencesKey("language")
    val SUPPORTED_LANGUAGES = stringPreferencesKey("supported_languages")
    val WHATS_NEW_KEY = booleanPreferencesKey("whats_new")
    val AUTO_RUN = booleanPreferencesKey("auto_run")
    val HIDE_SYSTEM_BARS = booleanPreferencesKey("hide_system_bars")
    val SCREEN_STAY_ON = booleanPreferencesKey("screen_stay_on")
    val TUTORIAL_KEY = booleanPreferencesKey("tutorial_enable")
    val MENU_CORNER_KEY = intPreferencesKey("menu_corner")

    val USER_SET_GPU_KEY = stringPreferencesKey("user_set_gpu")
    val USER_SET_TEMP_KEY = stringPreferencesKey("user_set_temp")
    val USER_SET_GPU_TEMP_KEY = stringPreferencesKey("user_set_gpu_temp")

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

    fun <T> getPreference(context: Context, key: Preferences.Key<T>, defaultValue: T): Flow<T> {
        return context.dataStore.data.map { preferences ->
            preferences[key] ?: defaultValue
        }
    }

    fun <T> getNullablePreference(context: Context, key: Preferences.Key<T>): Flow<T?> {
        return context.dataStore.data.map { preferences ->
            preferences[key]
        }
    }

    suspend fun <T> setPreference(context: Context, key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    suspend fun <T> setNullablePreference(context: Context, key: Preferences.Key<T>, value: T?) {
        context.dataStore.edit { preferences ->
            if (value != null) {
                preferences[key] = value
            } else {
                preferences.remove(key)
            }
        }
    }

    fun getOrCreateDefaultENVLine(context: Context): String {
        return runBlocking {
            val prefs = context.dataStore.data.first()
            val existing = prefs[ENV_LINE_KEY]
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val defaults = listOf(
                    "OPENMW_GLES_VERSION=32",
                    "UQM_CONFIG_DIR=${Constants.USER_FILE_STORAGE}/uqm",
                    "OSG_TEXT_SHADER_TECHNIQUE=ALL",
                    "OSG_VERTEX_BUFFER_HINT=VBO",
                    "DETHRACE_ROOT_DIR=${Constants.USER_FILE_STORAGE}/dethrace/",
                    "HARNESS_OPENGL=1"
                )
                val defaultStr = defaults.joinToString(" ")
                context.dataStore.edit { preferences ->
                    preferences[ENV_LINE_KEY] = defaultStr
                }
                defaultStr
            }
        }
    }
}
