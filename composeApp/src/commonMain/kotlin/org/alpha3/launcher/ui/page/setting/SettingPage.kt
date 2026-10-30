@file:OptIn(InternalCoroutinesApi::class, ExperimentalMaterial3Api::class)

package org.alpha3.launcher.ui.page.setting

import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.resources.allow_btn_group_feat
import org.alpha3.launcher.resources.allow_button_groups
import org.alpha3.launcher.resources.background_animations
import org.alpha3.launcher.resources.bouncing_background
import org.alpha3.launcher.resources.btn_cancel
import org.alpha3.launcher.resources.btn_confirm
import org.alpha3.launcher.resources.button_shape_changed_to_s
import org.alpha3.launcher.resources.bypass_the_game_files_check_tip
import org.alpha3.launcher.resources.cancel
import org.alpha3.launcher.resources.charset_changed_to
import org.alpha3.launcher.resources.circular_background
import org.alpha3.launcher.resources.configure_controls
import org.alpha3.launcher.resources.confirm_reset
import org.alpha3.launcher.resources.create_a_window_showing_battery_info
import org.alpha3.launcher.resources.create_a_window_showing_custom_log_output
import org.alpha3.launcher.resources.create_a_window_showing_logcat_info
import org.alpha3.launcher.resources.create_a_window_showing_memory_info
import org.alpha3.launcher.resources.custom_log_output
import org.alpha3.launcher.resources.customize_controls
import org.alpha3.launcher.resources.datastore_content
import org.alpha3.launcher.resources.developer_tools
import org.alpha3.launcher.resources.display_battery_info
import org.alpha3.launcher.resources.display_memory_info
import org.alpha3.launcher.resources.don_t_inject_resolution_settings_automatically
import org.alpha3.launcher.resources.enable_dangerous_feature_tip
import org.alpha3.launcher.resources.enable_developer_options
import org.alpha3.launcher.resources.enable_developer_options_feat
import org.alpha3.launcher.resources.enable_launcher_logcat
import org.alpha3.launcher.resources.enable_logcat
import org.alpha3.launcher.resources.enable_translation
import org.alpha3.launcher.utils.LocalTextTranslator
import org.alpha3.launcher.resources.glow_to_all_the_icons_feat
import org.alpha3.launcher.resources.high
import org.alpha3.launcher.resources.hybrid
import org.alpha3.launcher.resources.icon_glow
import org.alpha3.launcher.resources.interface_and_overlay
import org.alpha3.launcher.resources.launcher_settings
import org.alpha3.launcher.resources.logcat
import org.alpha3.launcher.resources.low
import org.alpha3.launcher.resources.match_icon_color_to_thumbstick
import org.alpha3.launcher.resources.medium
import org.alpha3.launcher.resources.mouse_mode
import org.alpha3.launcher.resources.mouse_mode_feat
import org.alpha3.launcher.resources.none
import org.alpha3.launcher.resources.open_file_browser
import org.alpha3.launcher.resources.openmw_settings
import org.alpha3.launcher.resources.overwriting_resolution_settings_tip
import org.alpha3.launcher.resources.reset_controls
import org.alpha3.launcher.resources.reset_the_controls_and_icons_tip
import org.alpha3.launcher.resources.return_to_launcher
import org.alpha3.launcher.resources.rotating_image_background
import org.alpha3.launcher.resources.save
import org.alpha3.launcher.resources.select_button_shape
import org.alpha3.launcher.resources.select_charset
import org.alpha3.launcher.resources.select_game
import org.alpha3.launcher.resources.select_language
import org.alpha3.launcher.resources.set_the_ui_visible_state_tip
import org.alpha3.launcher.resources.shape
import org.alpha3.launcher.resources.show_datastore_content
import org.alpha3.launcher.resources.system_and_debug
import org.alpha3.launcher.resources.this_enables_disables_the_launcher_files_check_tip
import org.alpha3.launcher.resources.this_enables_language_translation_feat
import org.alpha3.launcher.resources.thumbstick_share_its_color_feat
import org.alpha3.launcher.resources.ui_is_hidden
import org.alpha3.launcher.resources.ui_is_visible
import org.alpha3.launcher.resources.use_virtual_keyboard
import org.alpha3.launcher.resources.vibration_disabled
import org.alpha3.launcher.resources.vibration_enabled
import org.alpha3.launcher.resources.warning
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.utils.PlatformContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import kotlin.math.roundToInt
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import coil3.compose.LocalPlatformContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.controls.UIStateManager.configureControls
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.ui.controls.UIStateManager.openmwEngine
import org.alpha3.launcher.ui.controls.UIStateManager.openmwEngines
import org.alpha3.launcher.ui.controls.UIStateManager.gameList
import org.alpha3.launcher.ui.controls.UIStateManager.launchedActivity
import org.alpha3.launcher.ui.controls.UIStateManager.tempCodeGroup
import org.alpha3.launcher.ui.controls.stringToShape
import org.alpha3.launcher.ui.overlay.AnimationSettings
import org.alpha3.launcher.ui.page.mod.LandscapeSettings
import org.alpha3.launcher.ui.view.BouncingBackground
import org.alpha3.launcher.ui.view.CircularBackground
import org.alpha3.launcher.ui.view.MyTopBar
import org.alpha3.launcher.ui.view.NoneBackground
import org.alpha3.launcher.ui.view.RotatingImageBackground
import org.alpha3.launcher.utils.FileBrowserPopup
import org.alpha3.launcher.utils.DriverDebugEnvs
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.GpuFamily
import org.alpha3.launcher.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.alpha3.launcher.utils.IniSettings
import org.alpha3.launcher.utils.InitialDirectorySelection
import org.alpha3.launcher.utils.UserManageAssets
import org.alpha3.launcher.utils.getLayoutType
import org.alpha3.launcher.utils.startGame
import org.alpha3.launcher.utils.stringRes
import org.alpha3.launcher.io.File

@InternalCoroutinesApi
@ExperimentalMaterial3Api
@Composable
fun SettingPage(navigateToHome: () -> Unit) {
    val context = LocalPlatformContext.current
    val selectedBackgroundAnimation by GameFilesPreferences.getPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground").collectAsState(initial = "BouncingBackground")
    val newFeatureEnabledChecked by GameFilesPreferences.getPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false).collectAsState(initial = false)
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val layoutType = getLayoutType()

    LaunchedEffect(Unit) {
        UIStateManager.expandedSections.clear()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (layoutType == NavigationSuiteType.NavigationBar) {
                MyTopBar(context)
            }
        },
        content = { innerPadding ->
            when (selectedBackgroundAnimation) {
                "BouncingBackground" -> BouncingBackground()
                "RotatingImageBackground" -> RotatingImageBackground()
                "CircularBackground" -> CircularBackground()
                else -> NoneBackground()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (codeGroupOption == "OpenMW" && layoutType != NavigationSuiteType.NavigationBar) {
                    LandscapeSettings(isNewFeatureEnabledChecked = newFeatureEnabledChecked)
                } else LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (codeGroupOption == "OpenMW" && layoutType == NavigationSuiteType.NavigationBar) {
                        item { GeneralSettingsSection() }
                        item { GraphicsSettingsSection() }
                        item { ControlsSettingsSection() }
                        item { InterfaceSettingsSection() }
                        item { SystemSettingsSection() }

                        item {
                            SettingSectionCard(
                                title = stringResource(Res.string.openmw_settings),
                                icon = Icons.Default.Settings
                            ) {
                                IniSettings()
                            }
                        }

                        if (newFeatureEnabledChecked) {
                            item { DeveloperToolsSection() }
                        }
                    } else if (codeGroupOption == "UQM") {
                        item { UQM() }
                        item { GeneralSettingsSection() }
                        item { ControlsSettingsSection() }
                        if (newFeatureEnabledChecked) {
                            item { DeveloperToolsSection() }
                        }
                    } else if (codeGroupOption == "Dethrace") {
                        item { GeneralSettingsSection() }
                        item { ControlsSettingsSection() }
                        if (newFeatureEnabledChecked) {
                            item { DeveloperToolsSection() }
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun SettingSectionCard(
    title: String,
    icon: ImageVector,
    initialExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val isExpanded = UIStateManager.expandedSections.contains(title)

    LaunchedEffect(title) {
        if (initialExpanded && !UIStateManager.expandedSections.contains(title)) {
            UIStateManager.expandedSections.add(title)
        }
    }

    val anyExpanded = UIStateManager.expandedSections.isNotEmpty()
    val targetAlpha = if (isExpanded || !anyExpanded) 1f else 0.5f

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(targetAlpha),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = customColor.copy(alpha = 0.9f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isExpanded) UIStateManager.expandedSections.remove(title)
                        else UIStateManager.expandedSections.add(title)
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = White
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = White
                )
            }

            if (isExpanded) {
                HorizontalDivider(color = White.copy(alpha = 0.2f))
                Column(modifier = Modifier.padding(12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = White,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = White.copy(alpha = 0.7f)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        content()
    }
}

@Composable
fun GeneralSettingsSection() {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val translationChecked by GameFilesPreferences.getPreference(GameFilesPreferences.TRANSLATION_ENABLED_KEY, false).collectAsState(initial = false)

    SettingSectionCard(
        title = stringResource(Res.string.launcher_settings),
        icon = Icons.Default.Settings,
        initialExpanded = launchedActivity
    ) {
        CodeGroupOptionSelector()
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        
        // Only offered when the platform installed a translator (none on Android since ML Kit was removed)
        if (LocalTextTranslator.current != null) {
            SettingRow(
                title = stringResource(Res.string.enable_translation),
                subtitle = stringResource(Res.string.this_enables_language_translation_feat)
            ) {
                Switch(
                    checked = translationChecked,
                    onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.TRANSLATION_ENABLED_KEY, it) } }
                )
            }
        
            if (translationChecked) {
                LanguageSelector(context)
            }
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        CharsetDropdownMenu { updateCharset(it) }
    }
}

@Composable
fun GraphicsSettingsSection() {
    val scope = rememberCoroutineScope()
    // the bundled Turnip runs on Adreno only: on Mali the custom driver (PanVK) is something to opt into
    val mali = Platform.features.gpuDrivers?.gpuFamily == GpuFamily.MALI
    val useFreedreno by GameFilesPreferences.getPreference(GameFilesPreferences.USE_FREEDRENO, !mali).collectAsState(initial = !mali)
    val disableSwappy by GameFilesPreferences.getPreference(GameFilesPreferences.DISABLE_SWAPPY_KEY, false).collectAsState(initial = false)
    val frameGeneration by GameFilesPreferences.getPreference(GameFilesPreferences.FRAME_GENERATION_KEY, false).collectAsState(initial = false)

    SettingSectionCard(
        title = "Graphics & Performance",
        icon = Icons.Default.DisplaySettings
    ) {
        // Custom GPU drivers only exist where the platform has a driver manager (Android)
        if (Platform.features.gpuDrivers != null) {
            SettingRow(
                title = if (mali) "Use custom Vulkan driver" else "Use Freedreno driver",
                subtitle = if (mali) "Mesa PanVK, an open-source Mali driver (experimental). Off: the device's own driver."
                    else "Open-source Turnip Vulkan driver. Off: the device's own driver."
            ) {
                Switch(checked = useFreedreno, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.USE_FREEDRENO, it) } })
            }
            if (useFreedreno) GpuDriverSection()
        }

        // The stock OpenMW engine's OpenGL renderer (the fork renders with Vulkan)
        if (Platform.features.gpuDrivers != null) {
            val glRenderer by GameFilesPreferences.getPreference(GameFilesPreferences.GL_RENDERER_KEY, "gles").collectAsState(initial = "gles")
            var rendererExpanded by remember { mutableStateOf(false) }
            val rendererNames = linkedMapOf(
                "gles" to "OpenGL ES 3.2 (device driver)",
                "angle" to "ANGLE (OpenGL ES on Vulkan)",
                "zink" to "Zink (OpenGL on Vulkan)",
            )
            val rendererShort = mapOf("gles" to "Device", "angle" to "ANGLE", "zink" to "Zink")
            SettingRow(
                title = "Renderer (stock OpenMW)",
                subtitle = "Device is fastest. ANGLE and Zink run on Vulkan."
            ) {
                Box(modifier = Modifier.clickable { rendererExpanded = true }) {
                    Text(rendererShort[glRenderer] ?: "Device", modifier = Modifier.padding(8.dp), color = White)
                    DropdownMenu(expanded = rendererExpanded, onDismissRequest = { rendererExpanded = false }) {
                        rendererNames.forEach { (option, name) ->
                            DropdownMenuItem(
                                text = { Text(name, color = White) },
                                onClick = {
                                    scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.GL_RENDERER_KEY, option) }
                                    rendererExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            if (glRenderer == "zink" && !useFreedreno) {
                Text("Zink needs Vulkan 1.2+; the device's own driver may be older.", color = White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            if (glRenderer == "zink") {
                val budget by GameFilesPreferences.getPreference(GameFilesPreferences.ZINK_FRAME_BUDGET_KEY, 80).collectAsState(initial = 80)
                // follows the thumb while dragging, saved when let go
                var dragged by remember { mutableStateOf<Float?>(null) }
                val shown = dragged ?: budget.toFloat()
                SettingRow(
                    title = "Zink frame budget",
                    subtitle = "How much of each frame's time a frame may use before the game steps down to a steadier, lower " +
                        "frame rate. Lower keeps the phone cooler, 100% is as fast as the phone can hold."
                ) {
                    Text("${shown.roundToInt()}%", modifier = Modifier.padding(8.dp), color = White)
                }
                Slider(
                    value = shown,
                    onValueChange = { dragged = it },
                    onValueChangeFinished = {
                        val value = (dragged ?: budget.toFloat()).roundToInt()
                        scope.launch {
                            GameFilesPreferences.setPreference(GameFilesPreferences.ZINK_FRAME_BUDGET_KEY, value)
                            dragged = null
                        }
                    },
                    valueRange = 50f..100f,
                    steps = 9
                )
            }
            TexturePrepassRow()
        }

        // The renderers of a platform without custom GPU drivers (iOS: Apple's OpenGL ES or ANGLE on Metal)
        val glRenderers = Platform.features.glRenderers
        if (Platform.features.gpuDrivers == null && glRenderers.isNotEmpty()) {
            val default = glRenderers.keys.first()
            val glRenderer by GameFilesPreferences.getPreference(GameFilesPreferences.GL_RENDERER_KEY, default).collectAsState(initial = default)
            var rendererExpanded by remember { mutableStateOf(false) }
            SettingRow(
                title = "Renderer",
                subtitle = glRenderers[glRenderer] ?: glRenderers.getValue(default)
            ) {
                Box(modifier = Modifier.clickable { rendererExpanded = true }) {
                    Text("Change", modifier = Modifier.padding(8.dp), color = White)
                    DropdownMenu(expanded = rendererExpanded, onDismissRequest = { rendererExpanded = false }) {
                        glRenderers.forEach { (option, name) ->
                            DropdownMenuItem(
                                text = { Text(name, color = White) },
                                onClick = {
                                    scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.GL_RENDERER_KEY, option) }
                                    rendererExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            TexturePrepassRow()
        }

        // Frame pacing (Swappy) only exists on Android's OpenGL ES renderer
        if (Platform.features.gpuDrivers != null) {
            SettingRow(
                title = "Disable frame pacing (Swappy)",
                subtitle = "Plain buffer swaps instead of Swappy pacing."
            ) {
                Switch(checked = disableSwappy, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.DISABLE_SWAPPY_KEY, it) } })
            }
            SettingRow(
                title = "Frame generation (experimental)",
                subtitle = "Qualcomm motion estimation where the GPU has it; the stock engine falls back to a shader estimator. Needs frame pacing or Zink."
            ) {
                Switch(checked = frameGeneration, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.FRAME_GENERATION_KEY, it) } })
            }
            if (frameGeneration) {
                val multiplier by GameFilesPreferences.getPreference(GameFilesPreferences.FRAME_GENERATION_MULTIPLIER_KEY, 2).collectAsState(initial = 2)
                // follows the thumb while dragging, saved when let go
                var dragged by remember { mutableStateOf<Float?>(null) }
                val shown = dragged ?: multiplier.toFloat()
                SettingRow(
                    title = "Frame generation multiplier",
                    subtitle = "Most frames shown for each rendered one. It ramps up to this while the game keeps its frame rate."
                ) {
                    Text("${shown.roundToInt()}x", modifier = Modifier.padding(8.dp), color = White)
                }
                Slider(
                    value = shown,
                    onValueChange = { dragged = it },
                    onValueChangeFinished = {
                        val value = (dragged ?: multiplier.toFloat()).roundToInt()
                        scope.launch {
                            GameFilesPreferences.setPreference(GameFilesPreferences.FRAME_GENERATION_MULTIPLIER_KEY, value)
                            dragged = null
                        }
                    },
                    valueRange = 2f..4f,
                    steps = 1
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        BackgroundAnimationOptionSelector()
    }
}

@Composable
fun ControlsSettingsSection() {
    val scope = rememberCoroutineScope()
    val virtualKeyboard by GameFilesPreferences.getPreference(GameFilesPreferences.USE_VIRTUAL_KB, true).collectAsState(initial = true)
    val isVibrationOnFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
    val isVibrationOn by isVibrationOnFlow.collectAsState(initial = true)
    val buttonGroupSwitch by GameFilesPreferences.getPreference(GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, false).collectAsState(initial = false)

    SettingSectionCard(
        title = stringResource(Res.string.customize_controls),
        icon = Icons.Default.Gamepad
    ) {
        SettingRow(title = stringResource(Res.string.use_virtual_keyboard)) {
            Switch(checked = virtualKeyboard, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.USE_VIRTUAL_KB, it) } })
        }
        
        SettingRow(title = if (isVibrationOn) stringResource(Res.string.vibration_enabled) else stringResource(Res.string.vibration_disabled)) {
            Switch(checked = isVibrationOn, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.VIBRATION_STATE_KEY, it.toString()) } })
        }

        SettingRow(title = "Ignore controllers in the launcher", subtitle = "Keep the launcher's options available when a controller is connected. The controller still works in game") {
            Switch(checked = UIStateManager.ignoreController, onCheckedChange = {
                UIStateManager.ignoreController = it
                scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.IGNORE_CONTROLLER_KEY, it) }
            })
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        AutoMouseModeOptionSelector()
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        
        SettingRow(title = stringResource(Res.string.allow_button_groups), subtitle = stringResource(Res.string.allow_btn_group_feat)) {
            Switch(checked = buttonGroupSwitch, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, it) } })
        }
        
        ButtonShapeDropdownMenu { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.BUTTON_SHAPE, it) } }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        ControlsInsert()
    }
}

@Composable
fun InterfaceSettingsSection() {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
    val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
    val matchIconColorCheckedFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.MATCH_ICON_COLOR_KEY, "false").map { it.toBoolean() } }
    val matchIconColorChecked by matchIconColorCheckedFlow.collectAsState(initial = false)
    val iconGlowChecked by GameFilesPreferences.getPreference(GameFilesPreferences.ICON_GLOW_KEY, true).collectAsState(initial = true)
    val controllerConnected = Engine.isControllerConnected()

    SettingSectionCard(
        title = stringResource(Res.string.interface_and_overlay),
        icon = Icons.Default.ColorLens
    ) {
        SettingRow(
            title = if (controllerConnected || !isUIHidden) stringResource(Res.string.ui_is_visible) else stringResource(Res.string.ui_is_hidden),
            subtitle = stringResource(Res.string.set_the_ui_visible_state_tip)
        ) {
            Switch(checked = isUIHidden, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, it.toString()) } })
        }
        
        SettingRow(
            title = stringResource(Res.string.match_icon_color_to_thumbstick),
            subtitle = stringResource(Res.string.thumbstick_share_its_color_feat)
        ) {
            Switch(checked = matchIconColorChecked, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.MATCH_ICON_COLOR_KEY, it.toString()) } })
        }
        
        SettingRow(
            title = stringResource(Res.string.icon_glow),
            subtitle = stringResource(Res.string.glow_to_all_the_icons_feat)
        ) {
            Switch(checked = iconGlowChecked, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.ICON_GLOW_KEY, it) } })
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        AnimationSettings(context, scope)
    }
}

@Composable
fun SystemSettingsSection() {
    val scope = rememberCoroutineScope()
    val newFeatureEnabledChecked by GameFilesPreferences.getPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false).collectAsState(initial = false)
    var showWarningDialog by remember { mutableStateOf(false) }

    SettingSectionCard(
        title = stringResource(Res.string.system_and_debug),
        icon = Icons.Default.Computer
    ) {
        if (launchedActivity) {
            SettingRow(title = stringResource(Res.string.display_memory_info), subtitle = stringResource(Res.string.create_a_window_showing_memory_info)) {
                Switch(checked = UIStateManager.isMemoryInfoEnabled, onCheckedChange = { UIStateManager.isMemoryInfoEnabled = it })
            }
            SettingRow(title = stringResource(Res.string.display_battery_info), subtitle = stringResource(Res.string.create_a_window_showing_battery_info)) {
                Switch(checked = UIStateManager.isBatteryStatusEnabled, onCheckedChange = { UIStateManager.isBatteryStatusEnabled = it })
            }
            SettingRow(
                title = Platform.features.systemLogName ?: stringResource(Res.string.logcat),
                subtitle = Platform.features.systemLogName?.let { "Create a window showing the ${it.lowercase()}." }
                    ?: stringResource(Res.string.create_a_window_showing_logcat_info)
            ) {
                Switch(checked = UIStateManager.isLoggingEnabled, onCheckedChange = { UIStateManager.isLoggingEnabled = it })
            }
            SettingRow(title = stringResource(Res.string.custom_log_output), subtitle = stringResource(Res.string.create_a_window_showing_custom_log_output)) {
                Switch(checked = UIStateManager.isAppLoggingEnabled, onCheckedChange = { UIStateManager.isAppLoggingEnabled = it })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        }

        if (!launchedActivity) {
            SettingRow(
                title = stringResource(Res.string.enable_developer_options),
                subtitle = stringResource(Res.string.enable_developer_options_feat)
            ) {
                Switch(
                    checked = newFeatureEnabledChecked,
                    onCheckedChange = {
                        if (it) showWarningDialog = true
                        else scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false) }
                    }
                )
            }
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text(stringResource(Res.string.warning)) },
            text = { Text(stringResource(Res.string.enable_dangerous_feature_tip)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, true) }
                    showWarningDialog = false
                }) { Text(stringResource(Res.string.btn_confirm)) }
            },
            dismissButton = {
                Button(onClick = { showWarningDialog = false }) { Text(stringResource(Res.string.btn_cancel)) }
            }
        )
    }
}

@Composable
fun DeveloperToolsSection() {
    SettingSectionCard(
        title = stringResource(Res.string.developer_tools),
        icon = Icons.Default.Build
    ) {
        DevInsert()
    }
}

@Composable
fun DevInsert() {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val bypassGameCheck by GameFilesPreferences.getPreference(GameFilesPreferences.BYPASS_GAME_FILES_KEY, false).collectAsState(initial = false)
    val avoidInsertion by GameFilesPreferences.getPreference(GameFilesPreferences.AVOID_RESOLUTION_INSERTION, false).collectAsState(initial = false)
    val hideSystemBars by GameFilesPreferences.getPreference(GameFilesPreferences.HIDE_SYSTEM_BARS, false).collectAsState(initial = false)
    val screenStayOn by GameFilesPreferences.getPreference(GameFilesPreferences.SCREEN_STAY_ON, false).collectAsState(initial = false)
    val driverEnvs by GameFilesPreferences.getPreference(GameFilesPreferences.DRIVER_DEBUG_ENVS, "").collectAsState(initial = "")
    val selectedDriverEnvs = driverEnvs.split(",").filter { it.isNotEmpty() }.toSet()
    var driverOptionsExpanded by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var showFileBrowser by remember { mutableStateOf(false) }
    var selectedInitialDirectory by remember { mutableStateOf<File?>(null) }

    Column {
        // Saves Android's logcat when the game starts; iOS has no such buffer to save
        if (Platform.features.systemLogName == null) {
            SettingRow(title = stringResource(Res.string.enable_logcat), subtitle = stringResource(Res.string.enable_launcher_logcat)) {
                Switch(checked = UIStateManager.isLogcatEnabled, onCheckedChange = { UIStateManager.isLogcatEnabled = it })
            }
        }
        LogcatLevelSelector()
        SettingRow(title = "Show / Hide system bars") {
            Switch(checked = hideSystemBars, onCheckedChange = { isChecked -> scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.HIDE_SYSTEM_BARS, isChecked) } })
        }
        SettingRow(title = "Toggle screen stay on") {
            Switch(checked = screenStayOn, onCheckedChange = { isChecked -> scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.SCREEN_STAY_ON, isChecked) } })
        }
        SettingRow(title = stringResource(Res.string.don_t_inject_resolution_settings_automatically), subtitle = stringResource(Res.string.overwriting_resolution_settings_tip)) {
            Switch(checked = avoidInsertion, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.AVOID_RESOLUTION_INSERTION, it) } })
        }
        SettingRow(title = stringResource(Res.string.bypass_the_game_files_check_tip), subtitle = stringResource(Res.string.this_enables_disables_the_launcher_files_check_tip)) {
            Switch(checked = bypassGameCheck, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.BYPASS_GAME_FILES_KEY, it) } })
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))

        if (Platform.features.gpuDrivers != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { driverOptionsExpanded = !driverOptionsExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Freedreno driver debug options", color = White)
                    Text(
                        if (selectedDriverEnvs.isEmpty()) "None enabled" else "${selectedDriverEnvs.size} enabled",
                        color = White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
                Icon(
                    imageVector = if (driverOptionsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = White
                )
            }
            AnimatedVisibility(visible = driverOptionsExpanded) {
                Column {
                    Text("For testing garbled graphics. Applied on the next launch, and only with the Freedreno driver on.", color = White.copy(alpha = 0.6f), fontSize = 12.sp)
                    DriverDebugEnvs.all.forEach { env ->
                        SettingRow(title = env.title, subtitle = "${env.id}. ${env.subtitle}") {
                            Switch(checked = env.id in selectedDriverEnvs, onCheckedChange = { on ->
                                val updated = if (on) selectedDriverEnvs + env.id else selectedDriverEnvs - env.id
                                scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.DRIVER_DEBUG_ENVS, updated.joinToString(",")) }
                            })
                        }
                    }
                    Button(
                        onClick = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.DRIVER_DEBUG_ENVS, "") } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                    ) {
                        Text("Clear driver options", color = White)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        }

        Button(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(stringResource(Res.string.show_datastore_content), color = White)
        }

        Button(
            onClick = {
                selectedInitialDirectory = null
                showFileBrowser = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(stringResource(Res.string.open_file_browser), color = White)
        }
    }

    if (showPopup) {
        DataStoreContentPopup(context = context, onDismiss = { showPopup = false })
    }

    if (showFileBrowser && selectedInitialDirectory == null) {
        InitialDirectorySelection(
            onSelect = { directory ->
                selectedInitialDirectory = directory
                showFileBrowser = true
            },
            onDismiss = { showFileBrowser = false }
        )
    }

    if (showFileBrowser && selectedInitialDirectory != null) {
        FileBrowserPopup(initialDirectory = selectedInitialDirectory!!, onDismiss = { showFileBrowser = false })
    }
}

@Composable
fun ToggleFeatureSwitch() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (launchedActivity) {
            InGameSettings()
        } else {
            FeaturesSwitches()
        }
    }
}

@Composable
fun FeaturesSwitches() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GeneralSettingsSection()
        GraphicsSettingsSection()
        ControlsSettingsSection()
        InterfaceSettingsSection()
        SystemSettingsSection()
    }
}

@Composable
fun InGameSettings() {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    
    val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
    val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
    val virtualKeyboard by GameFilesPreferences.getPreference(GameFilesPreferences.USE_VIRTUAL_KB, true).collectAsState(initial = true)
    val isVibrationOnFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
    val isVibrationOn by isVibrationOnFlow.collectAsState(initial = true)
    val buttonGroupSwitch by GameFilesPreferences.getPreference(GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, false).collectAsState(initial = false)
    val controllerConnected = Engine.isControllerConnected()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (configureControls) {
            Button(
                onClick = {
                    launchedActivity = false
                    configureControls = false
                    Platform.features.restartLauncher()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C).copy(alpha = 0.8f),
                    contentColor = White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Text(
                    text = stringResource(Res.string.return_to_launcher),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // System & Debug Info
        SettingRow(title = "Performance HUD") {
            Switch(checked = UIStateManager.isPerformanceHudEnabled, onCheckedChange = { UIStateManager.isPerformanceHudEnabled = it })
        }
        SettingRow(title = stringResource(Res.string.display_memory_info)) {
            Switch(checked = UIStateManager.isMemoryInfoEnabled, onCheckedChange = { UIStateManager.isMemoryInfoEnabled = it })
        }
        SettingRow(title = stringResource(Res.string.display_battery_info)) {
            Switch(checked = UIStateManager.isBatteryStatusEnabled, onCheckedChange = { UIStateManager.isBatteryStatusEnabled = it })
        }
        SettingRow(title = Platform.features.systemLogName ?: stringResource(Res.string.logcat)) {
            Switch(checked = UIStateManager.isLoggingEnabled, onCheckedChange = { UIStateManager.isLoggingEnabled = it })
        }
        SettingRow(title = stringResource(Res.string.custom_log_output)) {
            Switch(checked = UIStateManager.isAppLoggingEnabled, onCheckedChange = { UIStateManager.isAppLoggingEnabled = it })
        }
        if (UIStateManager.tempCodeGroup == "UQM") {
            SettingRow(title = "UQM game info") {
                Switch(checked = UIStateManager.isUqmInfoEnabled, onCheckedChange = { UIStateManager.isUqmInfoEnabled = it })
            }
        }
        HorizontalDivider(color = White.copy(alpha = 0.1f))
        
        // Interface
        SettingRow(
            title = if (controllerConnected || !isUIHidden) stringResource(Res.string.ui_is_visible) else stringResource(Res.string.ui_is_hidden)
        ) {
            Switch(checked = isUIHidden, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, it.toString()) } })
        }
        
        // Controls
        SettingRow(title = stringResource(Res.string.use_virtual_keyboard)) {
            Switch(checked = virtualKeyboard, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.USE_VIRTUAL_KB, it) } })
        }
        SettingRow(title = if (isVibrationOn) stringResource(Res.string.vibration_enabled) else stringResource(Res.string.vibration_disabled)) {
            Switch(checked = isVibrationOn, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.VIBRATION_STATE_KEY, it.toString()) } })
        }
        SettingRow(title = "Ignore controllers in the launcher", subtitle = "Keep the launcher's options available when a controller is connected. The controller still works in game") {
            Switch(checked = UIStateManager.ignoreController, onCheckedChange = {
                UIStateManager.ignoreController = it
                scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.IGNORE_CONTROLLER_KEY, it) }
            })
        }
        SettingRow(title = stringResource(Res.string.allow_button_groups)) {
            Switch(checked = buttonGroupSwitch, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, it) } })
        }
        
        HorizontalDivider(color = White.copy(alpha = 0.1f))
        AutoMouseModeOptionSelector()
        
        HorizontalDivider(color = White.copy(alpha = 0.1f))
        AnimationSettings(context, scope)
    }
}
@Composable
fun LogcatLevelSelector() {
    val levels = listOf(
        "V" to "Verbose",
        "D" to "Debug",
        "I" to "Info",
        "W" to "Warning",
        "E" to "Error"
    )
    var expanded by remember { mutableStateOf(false) }
    val currentLevel = UIStateManager.logcatLevel

    SettingRow(
        title = "${Platform.features.systemLogName ?: "Logcat"} Filter Level",
        subtitle = "Select minimum priority level for ${Platform.features.systemLogName?.lowercase() ?: "logcat"} messages"
    ) {
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(
                    text = levels.find { it.first == currentLevel }?.second ?: "Verbose",
                    color = Color.Green
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(customColor)
            ) {
                levels.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = White) },
                        onClick = {
                            UIStateManager.logcatLevel = code
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
@Composable
fun DataStoreContentPopup(context: PlatformContext, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dataStoreContent by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        scope.launch {
            val preferences = dataStore.data.first()
            dataStoreContent = preferences.asMap().mapKeys { it.key.name }.mapValues { it.value.toString() }
        }
    }

    var updatedDataStoreContent by remember { mutableStateOf(dataStoreContent.toMutableMap()) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
                title = { Text(stringResource(Res.string.datastore_content)) },
                text = {
                    Column (
                        modifier = Modifier
                            .fillMaxHeight(0.6f) // Adjust the height as needed
                        .verticalScroll(rememberScrollState())
                    ) {
                        dataStoreContent.forEach { (key, value) ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = key)
                            var textFieldValue by remember { mutableStateOf(value) }
                            TextField(
                                value = textFieldValue,
                                onValueChange = { newValue ->
                                    textFieldValue = newValue
                                    updatedDataStoreContent[key] = newValue
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                            )
                            HorizontalDivider(
                                color = Color.Gray,
                                thickness = 1.dp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            updatedDataStoreContent.forEach { (key, value) ->
                                when (key) {
                                    GameFilesPreferences.GAME_FILES_URI_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.GAME_FILES_URI_KEY, value)
                                    GameFilesPreferences.UI_HIDDEN_STATE_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, value)
                                    GameFilesPreferences.MATCH_ICON_COLOR_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.MATCH_ICON_COLOR_KEY, value)
                                    GameFilesPreferences.RESOLUTION_X_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.RESOLUTION_X_KEY, value.toIntOrNull() ?: 0)
                                    GameFilesPreferences.RESOLUTION_Y_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.RESOLUTION_Y_KEY, value.toIntOrNull() ?: 0)
                                    GameFilesPreferences.ICON_GLOW_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.ICON_GLOW_KEY, value.toBoolean())
                                    GameFilesPreferences.ARG_LINE_KEY.name -> GameFilesPreferences.setNullablePreference(GameFilesPreferences.ARG_LINE_KEY, value)
                                    GameFilesPreferences.ENV_LINE_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.ENV_LINE_KEY, value)
                                    //GameFilesPreferences.NEXUS_API_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.NEXUS_API_KEY, value)
                                    GameFilesPreferences.USER_OPTIONS_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.USER_OPTIONS_KEY, value)
                                    GameFilesPreferences.LAUNCH_ENV_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.LAUNCH_ENV_KEY, value)
                                    GameFilesPreferences.LAUNCH_ARGS_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.LAUNCH_ARGS_KEY, value)
                                    GameFilesPreferences.LAUNCH_ARGS_CUSTOM_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.LAUNCH_ARGS_CUSTOM_KEY, value)
                                    GameFilesPreferences.AUTO_MOUSE_MODE_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, value)
                                    GameFilesPreferences.DISABLE_SWAPPY_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.DISABLE_SWAPPY_KEY, value.toBoolean())
                                    GameFilesPreferences.FRAME_GENERATION_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.FRAME_GENERATION_KEY, value.toBoolean())
                                    GameFilesPreferences.FRAME_GENERATION_MULTIPLIER_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.FRAME_GENERATION_MULTIPLIER_KEY, value.toIntOrNull() ?: 2)
                                    GameFilesPreferences.GL_RENDERER_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.GL_RENDERER_KEY, value)
                                    GameFilesPreferences.ZINK_FRAME_BUDGET_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.ZINK_FRAME_BUDGET_KEY, value.toIntOrNull() ?: 80)
                                    GameFilesPreferences.SELECTED_MOUSE_KEYS.name -> GameFilesPreferences.setPreference(GameFilesPreferences.SELECTED_MOUSE_KEYS, value)
                                    GameFilesPreferences.ALLOWED_TO_TEXT_EDITOR.name -> GameFilesPreferences.setPreference(GameFilesPreferences.ALLOWED_TO_TEXT_EDITOR, value)
                                    GameFilesPreferences.NEW_FEATURE_ENABLED_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, value.toBoolean())
                                    GameFilesPreferences.WHATS_NEW_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.WHATS_NEW_KEY, value.toBoolean())
                                    GameFilesPreferences.TRANSLATION_ENABLED_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.TRANSLATION_ENABLED_KEY, value.toBoolean())
                                    GameFilesPreferences.TUTORIAL_KEY.name -> GameFilesPreferences.setPreference(GameFilesPreferences.TUTORIAL_KEY, value.toBoolean())
                                    GameFilesPreferences.SUPPORTED_LANGUAGES.name -> GameFilesPreferences.setPreference(GameFilesPreferences.SUPPORTED_LANGUAGES, value)
                                }
                            }
                            onDismiss()
                        }
                    }, modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = customColor
                        )) {
                        Text(stringResource(Res.string.save), color = White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }, colors = ButtonDefaults.buttonColors(Color.Transparent)) {
                        Text(stringResource(Res.string.cancel), color = White)
                    }
                }
    )
}
@Composable
fun LanguageSelector(context: PlatformContext) {
    val scope = rememberCoroutineScope()
    val languageSelection by GameFilesPreferences.getPreference(GameFilesPreferences.LANGUAGE_KEY, "en").collectAsState(initial = "en")
    val supportedLanguagesFlow = remember {
        dataStore.data.map { preferences ->
            preferences[GameFilesPreferences.SUPPORTED_LANGUAGES]?.split(",")?.map { it.trim() } ?: run {
                val defaultLanguages = "en,ru,hr,fr,es,de,it,pt,ja,zh,ar,hi,pl,uk,vi,ko,th,bn,ta,ur,cs"
                scope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.SUPPORTED_LANGUAGES, defaultLanguages) }
                defaultLanguages.split(",").map { it.trim() }
            }
        }
    }
    val supportedLanguages by supportedLanguagesFlow.collectAsState(initial = listOf("en", "ru", "hr", "fr"))
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = customColor)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(Res.string.select_language), color = White)
            Box(
                modifier = Modifier
                    .clickable { expanded = true }
            ) {
                Text(languageSelection, modifier = Modifier.padding(8.dp), color = White)
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .border(1.dp, Color.Black)
                ) {
                    supportedLanguages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language, color = White) },
                            onClick = {
                                scope.launch {
                                    try {
                                        GameFilesPreferences.setPreference(GameFilesPreferences.LANGUAGE_KEY, language)
                                        println("Language set to: $language")
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CodeGroupOptionSelector() {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = customColor)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(Res.string.select_game), color = White)
            Box(
                modifier = Modifier
                    .clickable { expanded = true }
            ) {
                Text(codeGroupOption, modifier = Modifier.padding(8.dp), color = White)
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .border(1.dp, Color.Black)
                ) {
                    gameList.forEach { game ->
                        DropdownMenuItem(
                            text = { Text(game, color = White) },
                            onClick = {
                                scope.launch {
                                    try {
                                        GameFilesPreferences.setPreference(GameFilesPreferences.CODE_GROUP_KEY, game)
                                        tempCodeGroup = game
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        if (codeGroupOption == "OpenMW" && UIStateManager.forkEngineAvailable) {
            var engineExpanded by remember { mutableStateOf(false) }
            val engine by GameFilesPreferences.getPreference(GameFilesPreferences.OPENMW_ENGINE_KEY, "upstream").collectAsState(initial = "upstream")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = customColor)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("OpenMW engine", color = White)
                Box(modifier = Modifier.clickable { engineExpanded = true }) {
                    Text(if (engine == "upstream") "OpenMW (stock, OpenGL)" else "OpenMW (clockwork, Vulkan)", modifier = Modifier.padding(8.dp), color = White)
                    DropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false },
                        modifier = Modifier.border(1.dp, Color.Black)
                    ) {
                        openmwEngines.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == "upstream") "OpenMW (stock, OpenGL)" else "OpenMW (clockwork, Vulkan)", color = White) },
                                onClick = {
                                    scope.launch {
                                        try {
                                            GameFilesPreferences.setPreference(GameFilesPreferences.OPENMW_ENGINE_KEY, option)
                                            openmwEngine = option
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                    engineExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutoMouseModeOptionSelector() {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val autoMouseMode by GameFilesPreferences.getPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid").collectAsState(initial = "Hybrid")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = customColor)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
                modifier = Modifier
                    .weight(1f)
                    .background(color = customColor)
            ) {
                Text(
                    text = stringResource(Res.string.mouse_mode),
                    color = White
                )
                Text(
                    text = stringResource(Res.string.mouse_mode_feat),
                    color = White,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                )
            }
            Box(
                modifier = Modifier
                    .clickable { expanded = true }
            ) {
                Text(autoMouseMode, modifier = Modifier.padding(8.dp), color = White)
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .border(1.dp, Color.Black)
                        .background(color = customColor)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.none), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "None")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.hybrid), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                }
            }
        }
    }
}

@Composable
fun CharsetDropdownMenu(updateCharset: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val charsets = listOf("utf-8", "utf-16", "us-ascii", "iso-8859-1", "shift_jis", "euc-jp", "win1250", "win1251", "windows-1252", "gbk")
    var selectedCharset by remember { mutableStateOf(readCurrentCharset()) }
    var feedbackMessage by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .padding(16.dp)
            .background(color = customColor)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.select_charset), modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = { expanded = true }) {
            Text(selectedCharset)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .border(1.dp, Color.Black)
        ) {
            charsets.forEach { charset ->
                DropdownMenuItem(
                    text = { Text(charset, color = White) },
                    onClick = {
                        selectedCharset = charset
                        expanded = false
                        updateCharset(charset)

                        feedbackMessage = "${stringRes(Res.string.charset_changed_to)} $charset"
                    }
                )
            }
        }
    }
    if (feedbackMessage.isNotEmpty()) {
        Text(feedbackMessage, color = Color.Green, modifier = Modifier.padding(8.dp))
    }
}

fun readCurrentCharset(): String {
    val configFilePath = OpenMWPaths.OPENMW_CFG
    val file = File(configFilePath)
    var encodingLine = file.readLines().find { it.startsWith("encoding=") }
    if (encodingLine == null) {
        encodingLine = "encoding=win1252"
        file.appendText("\n$encodingLine")
    }
    return encodingLine.split("=")[1]
}

fun updateCharset(newEncoding: String) {
    val configFilePath = OpenMWPaths.OPENMW_CFG
    val file = File(configFilePath)
    val updatedLines = file.readLines().map { line ->
        if (line.startsWith("encoding=")) {
            "encoding=$newEncoding"
        } else {
            line
        }
    }
    file.writeText(updatedLines.joinToString("\n"))
    println("Charset changed to $newEncoding")
}


@Composable
fun BackgroundAnimationOptionSelector() {
    val backgroundAnimation by GameFilesPreferences.getPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground").collectAsState(initial = "BouncingBackground")
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = customColor)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(Res.string.background_animations), color = White)
            Box(
                modifier = Modifier
                    .clickable { expanded = true }
            ) {
                Text(backgroundAnimation, modifier = Modifier.padding(8.dp), color = White)
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .border(1.dp, Color.Black)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.none), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "None")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.bouncing_background), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.rotating_image_background), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "RotatingImageBackground")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.circular_background), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "CircularBackground")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                }
            }
        }
    }
}

@Composable
fun ControlsInsert() {
    val context = LocalPlatformContext.current
    var showDialog2 by remember { mutableStateOf(false) }
    HorizontalDivider(color = White, thickness = 1.dp)
    Button(
        onClick = {
            configureControls = true
            context.startGame()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        )
    ) {
        Text(stringResource(Res.string.configure_controls), color = White)
    }
    HorizontalDivider(color = White, thickness = 1.dp)
    Button(
        onClick = {
            showDialog2 = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        )
    ) {
        Text(stringResource(Res.string.reset_controls), color = White)
    }
    HorizontalDivider(color = White, thickness = 1.dp)

    if (showDialog2) {
        AlertDialog(
            onDismissRequest = {
                showDialog2 = false
            },
            title = {
                Text(text = stringResource(Res.string.confirm_reset), color = White)
            },
            text = {
                Text(
                    stringResource(Res.string.reset_the_controls_and_icons_tip),
                    color = White
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        UserManageAssets(context).resetUI()
                        showDialog2 = false
                    }, colors = ButtonDefaults.buttonColors(Color.Transparent)
                ) {
                    Text(stringResource(Res.string.btn_confirm), color = White)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showDialog2 = false
                    }, colors = ButtonDefaults.buttonColors(Color.Transparent)
                ) {
                    Text(stringResource(Res.string.btn_cancel), color = White)
                }
            }
        )
    }
}

@Composable
fun UQM() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(color = White, thickness = 1.dp)
        CodeGroupOptionSelector()
    }
}

@Composable
fun ButtonShapeDropdownMenu(updateShape: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shapes = listOf("CircleShape", "RoundedCornerShape", "RectangleShape", "StadiumShape")
    val currentShape by GameFilesPreferences.getPreference(GameFilesPreferences.BUTTON_SHAPE, "CircleShape").collectAsState(initial = "StadiumShape")
    var feedbackMessage by remember { mutableStateOf("") }
    val buttonShape = stringToShape(currentShape)

    Row(
        modifier = Modifier
            .padding(16.dp)
            .background(color = customColor)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.select_button_shape), color = White, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = { expanded = true },
            modifier = Modifier,
            shape = buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = customColor,
                contentColor = Color.Black
            )
        ) {
            Text(stringResource(Res.string.shape), color = White)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .border(1.dp, Color.Black)
                .background(color = customColor)
        ) {
            shapes.forEach { shape ->
                DropdownMenuItem(
                    text = { Text(shape, color = White) },
                    onClick = {
                        updateShape(shape)
                        expanded = false
                        feedbackMessage = stringRes(Res.string.button_shape_changed_to_s, shape)
                    }
                )
            }
        }
    }

    if (feedbackMessage.isNotEmpty()) {
        Text(feedbackMessage, color = Color.Green, modifier = Modifier.padding(8.dp))
    }
}
