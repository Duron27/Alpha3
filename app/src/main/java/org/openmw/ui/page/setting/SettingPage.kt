@file:OptIn(InternalCoroutinesApi::class, ExperimentalMaterial3Api::class)

package org.openmw.ui.page.setting

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import org.openmw.Constants
import org.openmw.MainActivity
import org.openmw.R
import org.openmw.isControllerConnected
import org.openmw.ui.controls.UIStateManager
import org.openmw.ui.controls.UIStateManager.configureControls
import org.openmw.ui.controls.UIStateManager.customColor
import org.openmw.ui.controls.UIStateManager.gameList
import org.openmw.ui.controls.UIStateManager.launchedActivity
import org.openmw.ui.controls.UIStateManager.tempCodeGroup
import org.openmw.ui.controls.stringToShape
import org.openmw.ui.overlay.AnimationSettings
import org.openmw.ui.page.mod.LandscapeSettings
import org.openmw.ui.view.BouncingBackground
import org.openmw.ui.view.CircularBackground
import org.openmw.ui.view.MyTopBar
import org.openmw.ui.view.NoneBackground
import org.openmw.ui.view.RotatingImageBackground
import org.openmw.utils.FileBrowserPopup
import org.openmw.utils.GameFilesPreferences
import org.openmw.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.openmw.utils.IniSettings
import org.openmw.utils.InitialDirectorySelection
import org.openmw.utils.UserManageAssets
import org.openmw.utils.getLayoutType
import org.openmw.utils.startGame
import org.openmw.utils.stringRes
import java.io.File

@InternalCoroutinesApi
@ExperimentalMaterial3Api
@Composable
fun SettingPage(navigateToHome: () -> Unit) {
    val context = LocalContext.current
    val selectedBackgroundAnimation by GameFilesPreferences.getPreference(context, GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground").collectAsState(initial = "BouncingBackground")
    val newFeatureEnabledChecked by GameFilesPreferences.getPreference(context, GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false).collectAsState(initial = false)
    val codeGroupOption by GameFilesPreferences.getPreference(context, GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
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
                LazyColumn(
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
                                title = stringResource(R.string.openmw_settings),
                                icon = Icons.Default.Settings
                            ) {
                                IniSettings()
                            }
                        }

                        if (newFeatureEnabledChecked) {
                            item { DeveloperToolsSection() }
                        }
                    } else if (codeGroupOption == "OpenMW") {
                        item {
                            LandscapeSettings(isNewFeatureEnabledChecked = newFeatureEnabledChecked)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translationChecked by GameFilesPreferences.getPreference(context, GameFilesPreferences.TRANSLATION_ENABLED_KEY, false).collectAsState(initial = false)

    SettingSectionCard(
        title = stringResource(R.string.launcher_settings),
        icon = Icons.Default.Settings,
        initialExpanded = launchedActivity
    ) {
        CodeGroupOptionSelector()
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        
        SettingRow(
            title = stringResource(R.string.enable_translation),
            subtitle = stringResource(R.string.this_enables_language_translation_feat)
        ) {
            Switch(
                checked = translationChecked,
                onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.TRANSLATION_ENABLED_KEY, it) } }
            )
        }
        
        if (translationChecked) {
            LanguageSelector(context)
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        CharsetDropdownMenu { updateCharset(it) }
    }
}

@Composable
fun GraphicsSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avoid16BitsChecked by GameFilesPreferences.getPreference(context, GameFilesPreferences.AVOID_16_BITS_KEY, true).collectAsState(initial = true)
    val useAngle by GameFilesPreferences.getPreference(context, GameFilesPreferences.USE_ANGLE, false).collectAsState(initial = false)
    val useSPIRV by GameFilesPreferences.getPreference(context, GameFilesPreferences.USE_SPIRV, false).collectAsState(initial = false)

    SettingSectionCard(
        title = "Graphics & Performance",
        icon = Icons.Default.DisplaySettings
    ) {
        SettingRow(
            title = "Use Angle driver",
            subtitle = "Using this setting will enable newer shaders on older hardware like shadows."
        ) {
            Switch(checked = useAngle, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.USE_ANGLE, it) } })
        }
        
        SettingRow(
            title = "Enable SPIRV",
            subtitle = "Using this setting will enable newer shaders on hardware that doesn't support it."
        ) {
            Switch(checked = useSPIRV, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.USE_SPIRV, it) } })
        }
        
        SettingRow(
            title = stringResource(R.string.avoid_16_bits),
            subtitle = stringResource(R.string.helps_with_devices_that_have_less_memory_feat)
        ) {
            Switch(checked = avoid16BitsChecked, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.AVOID_16_BITS_KEY, it) } })
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        TextureShrinkingOptionSelector()
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        BackgroundAnimationOptionSelector()
    }
}

@Composable
fun ControlsSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val virtualKeyboard by GameFilesPreferences.getPreference(context, GameFilesPreferences.USE_VIRTUAL_KB, true).collectAsState(initial = true)
    val isVibrationOnFlow = remember { GameFilesPreferences.getPreference(context, GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
    val isVibrationOn by isVibrationOnFlow.collectAsState(initial = true)
    val buttonGroupSwitch by GameFilesPreferences.getPreference(context, GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, false).collectAsState(initial = false)

    SettingSectionCard(
        title = stringResource(R.string.customize_controls),
        icon = Icons.Default.Gamepad
    ) {
        SettingRow(title = stringResource(R.string.use_virtual_keyboard)) {
            Switch(checked = virtualKeyboard, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.USE_VIRTUAL_KB, it) } })
        }
        
        SettingRow(title = if (isVibrationOn) stringResource(R.string.vibration_enabled) else stringResource(R.string.vibration_disabled)) {
            Switch(checked = isVibrationOn, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.VIBRATION_STATE_KEY, it.toString()) } })
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        AutoMouseModeOptionSelector()
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        
        SettingRow(title = stringResource(R.string.allow_button_groups), subtitle = stringResource(R.string.allow_btn_group_feat)) {
            Switch(checked = buttonGroupSwitch, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, it) } })
        }
        
        ButtonShapeDropdownMenu { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.BUTTON_SHAPE, it) } }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        ControlsInsert()
    }
}

@Composable
fun InterfaceSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(context, GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
    val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
    val matchIconColorCheckedFlow = remember { GameFilesPreferences.getPreference(context, GameFilesPreferences.MATCH_ICON_COLOR_KEY, "false").map { it.toBoolean() } }
    val matchIconColorChecked by matchIconColorCheckedFlow.collectAsState(initial = false)
    val iconGlowChecked by GameFilesPreferences.getPreference(context, GameFilesPreferences.ICON_GLOW_KEY, true).collectAsState(initial = true)
    val controllerConnected = isControllerConnected(context)

    SettingSectionCard(
        title = stringResource(R.string.interface_and_overlay),
        icon = Icons.Default.ColorLens
    ) {
        SettingRow(
            title = if (controllerConnected || !isUIHidden) stringResource(R.string.ui_is_visible) else stringResource(R.string.ui_is_hidden),
            subtitle = stringResource(R.string.set_the_ui_visible_state_tip)
        ) {
            Switch(checked = isUIHidden, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.UI_HIDDEN_STATE_KEY, it.toString()) } })
        }
        
        SettingRow(
            title = stringResource(R.string.match_icon_color_to_thumbstick),
            subtitle = stringResource(R.string.thumbstick_share_its_color_feat)
        ) {
            Switch(checked = matchIconColorChecked, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.MATCH_ICON_COLOR_KEY, it.toString()) } })
        }
        
        SettingRow(
            title = stringResource(R.string.icon_glow),
            subtitle = stringResource(R.string.glow_to_all_the_icons_feat)
        ) {
            Switch(checked = iconGlowChecked, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.ICON_GLOW_KEY, it) } })
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        AnimationSettings(context, scope)
    }
}

@Composable
fun SystemSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val newFeatureEnabledChecked by GameFilesPreferences.getPreference(context, GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false).collectAsState(initial = false)
    var showWarningDialog by remember { mutableStateOf(false) }

    SettingSectionCard(
        title = stringResource(R.string.system_and_debug),
        icon = Icons.Default.Computer
    ) {
        if (launchedActivity) {
            SettingRow(title = stringResource(R.string.display_memory_info), subtitle = stringResource(R.string.create_a_window_showing_memory_info)) {
                Switch(checked = UIStateManager.isMemoryInfoEnabled, onCheckedChange = { UIStateManager.isMemoryInfoEnabled = it })
            }
            SettingRow(title = stringResource(R.string.display_battery_info), subtitle = stringResource(R.string.create_a_window_showing_battery_info)) {
                Switch(checked = UIStateManager.isBatteryStatusEnabled, onCheckedChange = { UIStateManager.isBatteryStatusEnabled = it })
            }
            SettingRow(title = stringResource(R.string.logcat), subtitle = stringResource(R.string.create_a_window_showing_logcat_info)) {
                Switch(checked = UIStateManager.isLoggingEnabled, onCheckedChange = { UIStateManager.isLoggingEnabled = it })
            }
            SettingRow(title = stringResource(R.string.custom_log_output), subtitle = stringResource(R.string.create_a_window_showing_custom_log_output)) {
                Switch(checked = UIStateManager.isAppLoggingEnabled, onCheckedChange = { UIStateManager.isAppLoggingEnabled = it })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))
        }

        if (!launchedActivity) {
            SettingRow(
                title = stringResource(R.string.enable_developer_options),
                subtitle = stringResource(R.string.enable_developer_options_feat)
            ) {
                Switch(
                    checked = newFeatureEnabledChecked,
                    onCheckedChange = {
                        if (it) showWarningDialog = true
                        else scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false) }
                    }
                )
            }
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text(stringResource(R.string.warning)) },
            text = { Text(stringResource(R.string.enable_dangerous_feature_tip)) },
            confirmButton = {
                Button(onClick = {
                    scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, true) }
                    showWarningDialog = false
                }) { Text(stringResource(R.string.btn_confirm)) }
            },
            dismissButton = {
                Button(onClick = { showWarningDialog = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

@Composable
fun DeveloperToolsSection() {
    SettingSectionCard(
        title = stringResource(R.string.developer_tools),
        icon = Icons.Default.Build
    ) {
        DevInsert()
    }
}

@Composable
fun DevInsert() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bypassGameCheck by GameFilesPreferences.getPreference(context, GameFilesPreferences.BYPASS_GAME_FILES_KEY, false).collectAsState(initial = false)
    val avoidInsertion by GameFilesPreferences.getPreference(context, GameFilesPreferences.AVOID_RESOLUTION_INSERTION, false).collectAsState(initial = false)
    val hideSystemBars by GameFilesPreferences.getPreference(context, GameFilesPreferences.HIDE_SYSTEM_BARS, false).collectAsState(initial = false)
    val screenStayOn by GameFilesPreferences.getPreference(context, GameFilesPreferences.SCREEN_STAY_ON, false).collectAsState(initial = false)
    var showPopup by remember { mutableStateOf(false) }
    var showFileBrowser by remember { mutableStateOf(false) }
    var selectedInitialDirectory by remember { mutableStateOf<File?>(null) }

    Column {
        SettingRow(title = stringResource(R.string.enable_logcat), subtitle = stringResource(R.string.enable_launcher_logcat)) {
            Switch(checked = UIStateManager.isLogcatEnabled, onCheckedChange = { UIStateManager.isLogcatEnabled = it })
        }
        LogcatLevelSelector()
        SettingRow(title = "Show / Hide system bars") {
            Switch(checked = hideSystemBars, onCheckedChange = { isChecked -> scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.HIDE_SYSTEM_BARS, isChecked) } })
        }
        SettingRow(title = "Toggle screen stay on") {
            Switch(checked = screenStayOn, onCheckedChange = { isChecked -> scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.SCREEN_STAY_ON, isChecked) } })
        }
        SettingRow(title = stringResource(R.string.don_t_inject_resolution_settings_automatically), subtitle = stringResource(R.string.overwriting_resolution_settings_tip)) {
            Switch(checked = avoidInsertion, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.AVOID_RESOLUTION_INSERTION, it) } })
        }
        SettingRow(title = stringResource(R.string.bypass_the_game_files_check_tip), subtitle = stringResource(R.string.this_enables_disables_the_launcher_files_check_tip)) {
            Switch(checked = bypassGameCheck, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.BYPASS_GAME_FILES_KEY, it) } })
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = White.copy(alpha = 0.1f))

        Button(
            onClick = { showPopup = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(stringResource(R.string.show_datastore_content), color = White)
        }

        Button(
            onClick = {
                selectedInitialDirectory = null
                showFileBrowser = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(stringResource(R.string.open_file_browser), color = White)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(context, GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
    val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
    val virtualKeyboard by GameFilesPreferences.getPreference(context, GameFilesPreferences.USE_VIRTUAL_KB, true).collectAsState(initial = true)
    val isVibrationOnFlow = remember { GameFilesPreferences.getPreference(context, GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
    val isVibrationOn by isVibrationOnFlow.collectAsState(initial = true)
    val buttonGroupSwitch by GameFilesPreferences.getPreference(context, GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, false).collectAsState(initial = false)
    val controllerConnected = isControllerConnected(context)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (configureControls) {
            Button(
                onClick = {
                    launchedActivity = false
                    configureControls = false
                    context.startActivity(Intent(context, MainActivity::class.java))
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
                    text = stringResource(R.string.return_to_launcher),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // System & Debug Info
        SettingRow(title = "Performance HUD") {
            Switch(checked = UIStateManager.isPerformanceHudEnabled, onCheckedChange = { UIStateManager.isPerformanceHudEnabled = it })
        }
        SettingRow(title = stringResource(R.string.display_memory_info)) {
            Switch(checked = UIStateManager.isMemoryInfoEnabled, onCheckedChange = { UIStateManager.isMemoryInfoEnabled = it })
        }
        SettingRow(title = stringResource(R.string.display_battery_info)) {
            Switch(checked = UIStateManager.isBatteryStatusEnabled, onCheckedChange = { UIStateManager.isBatteryStatusEnabled = it })
        }
        SettingRow(title = stringResource(R.string.logcat)) {
            Switch(checked = UIStateManager.isLoggingEnabled, onCheckedChange = { UIStateManager.isLoggingEnabled = it })
        }
        SettingRow(title = stringResource(R.string.custom_log_output)) {
            Switch(checked = UIStateManager.isAppLoggingEnabled, onCheckedChange = { UIStateManager.isAppLoggingEnabled = it })
        }
        HorizontalDivider(color = White.copy(alpha = 0.1f))
        
        // Interface
        SettingRow(
            title = if (controllerConnected || !isUIHidden) stringResource(R.string.ui_is_visible) else stringResource(R.string.ui_is_hidden)
        ) {
            Switch(checked = isUIHidden, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.UI_HIDDEN_STATE_KEY, it.toString()) } })
        }
        
        // Controls
        SettingRow(title = stringResource(R.string.use_virtual_keyboard)) {
            Switch(checked = virtualKeyboard, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.USE_VIRTUAL_KB, it) } })
        }
        SettingRow(title = if (isVibrationOn) stringResource(R.string.vibration_enabled) else stringResource(R.string.vibration_disabled)) {
            Switch(checked = isVibrationOn, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.VIBRATION_STATE_KEY, it.toString()) } })
        }
        SettingRow(title = stringResource(R.string.allow_button_groups)) {
            Switch(checked = buttonGroupSwitch, onCheckedChange = { scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, it) } })
        }
        
        HorizontalDivider(color = White.copy(alpha = 0.1f))
        AutoMouseModeOptionSelector()
        
        HorizontalDivider(color = White.copy(alpha = 0.1f))
        AnimationSettings(context, scope)
    }
}

@SuppressLint("MutableCollectionMutableState")
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
        title = "Logcat Filter Level",
        subtitle = "Select minimum priority level for logcat messages"
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
fun DataStoreContentPopup(context: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var dataStoreContent by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        scope.launch {
            val preferences = context.dataStore.data.first()
            dataStoreContent = preferences.asMap().mapKeys { it.key.name }.mapValues { it.value.toString() }
        }
    }

    var updatedDataStoreContent by remember { mutableStateOf(dataStoreContent.toMutableMap()) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
                title = { Text(stringResource(R.string.datastore_content)) },
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
                                    GameFilesPreferences.GAME_FILES_URI_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.GAME_FILES_URI_KEY, value)
                                    GameFilesPreferences.UI_HIDDEN_STATE_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.UI_HIDDEN_STATE_KEY, value)
                                    GameFilesPreferences.MATCH_ICON_COLOR_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.MATCH_ICON_COLOR_KEY, value)
                                    GameFilesPreferences.RESOLUTION_X_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.RESOLUTION_X_KEY, value.toIntOrNull() ?: 0)
                                    GameFilesPreferences.RESOLUTION_Y_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.RESOLUTION_Y_KEY, value.toIntOrNull() ?: 0)
                                    GameFilesPreferences.ICON_GLOW_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.ICON_GLOW_KEY, value.toBoolean())
                                    GameFilesPreferences.ARG_LINE_KEY.name -> GameFilesPreferences.setNullablePreference(context, GameFilesPreferences.ARG_LINE_KEY, value)
                                    GameFilesPreferences.ENV_LINE_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.ENV_LINE_KEY, value)
                                    //GameFilesPreferences.NEXUS_API_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.NEXUS_API_KEY, value)
                                    GameFilesPreferences.USER_OPTIONS_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.USER_OPTIONS_KEY, value)
                                    GameFilesPreferences.AUTO_MOUSE_MODE_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.AUTO_MOUSE_MODE_KEY, value)
                                    GameFilesPreferences.AVOID_16_BITS_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.AVOID_16_BITS_KEY, value.toBoolean())
                                    GameFilesPreferences.TEXTURE_SHRINKING_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.TEXTURE_SHRINKING_KEY, value)
                                    GameFilesPreferences.SELECTED_MOUSE_KEYS.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.SELECTED_MOUSE_KEYS, value)
                                    GameFilesPreferences.ALLOWED_TO_TEXT_EDITOR.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.ALLOWED_TO_TEXT_EDITOR, value)
                                    GameFilesPreferences.NEW_FEATURE_ENABLED_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, value.toBoolean())
                                    GameFilesPreferences.WHATS_NEW_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.WHATS_NEW_KEY, value.toBoolean())
                                    GameFilesPreferences.TRANSLATION_ENABLED_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.TRANSLATION_ENABLED_KEY, value.toBoolean())
                                    GameFilesPreferences.TUTORIAL_KEY.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.TUTORIAL_KEY, value.toBoolean())
                                    GameFilesPreferences.SUPPORTED_LANGUAGES.name -> GameFilesPreferences.setPreference(context, GameFilesPreferences.SUPPORTED_LANGUAGES, value)
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
                        Text(stringResource(R.string.save), color = White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onDismiss() }, colors = ButtonDefaults.buttonColors(Color.Transparent)) {
                        Text(stringResource(R.string.cancel), color = White)
                    }
                }
    )
}


@SuppressLint("SuspiciousIndentation")
@Composable
fun TextureShrinkingOptionSelector() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textureShrinkingOption by GameFilesPreferences.getPreference(context, GameFilesPreferences.TEXTURE_SHRINKING_KEY, "None").collectAsState(initial = "None")
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
            Text(stringResource(R.string.texture_shrinking_option), color = White)
            Box(
                modifier = Modifier
                .clickable { expanded = true }
            ) {
                Text(textureShrinkingOption, modifier = Modifier.padding(8.dp), color = White)
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .border(1.dp, Color.Black)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.none), color = White) },
                                     onClick = {
                                         scope.launch {
                                             try {
                                                 GameFilesPreferences.setPreference(context, GameFilesPreferences.TEXTURE_SHRINKING_KEY, "None")
                                             } catch (e: Exception) {
                                                 e.printStackTrace()
                                             }
                                         }
                                         expanded = false
                                     })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.low), color = White) },
                                     onClick = {
                                         scope.launch {
                                             try {
                                                 GameFilesPreferences.setPreference(context, GameFilesPreferences.TEXTURE_SHRINKING_KEY, "low")
                                             } catch (e: Exception) {
                                                 e.printStackTrace()
                                             }
                                         }
                                         expanded = false
                                     })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.medium), color = White) },
                                     onClick = {
                                         scope.launch {
                                             try {
                                                 GameFilesPreferences.setPreference(context, GameFilesPreferences.TEXTURE_SHRINKING_KEY, "medium")
                                             } catch (e: Exception) {
                                                 e.printStackTrace()
                                             }
                                         }
                                         expanded = false
                                     })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.high), color = White) },
                        onClick = {
                                scope.launch {
                                    try {
                                        GameFilesPreferences.setPreference(context, GameFilesPreferences.TEXTURE_SHRINKING_KEY, "high")
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
fun LanguageSelector(context: Context) {
    val scope = rememberCoroutineScope()
    val languageSelection by GameFilesPreferences.getPreference(context, GameFilesPreferences.LANGUAGE_KEY, "en").collectAsState(initial = "en")
    val supportedLanguagesFlow = remember {
        context.dataStore.data.map { preferences ->
            preferences[GameFilesPreferences.SUPPORTED_LANGUAGES]?.split(",")?.map { it.trim() } ?: run {
                val defaultLanguages = "en,ru,hr,fr,es,de,it,pt,ja,zh,ar,hi,pl,uk,vi,ko,th,bn,ta,ur,cs"
                scope.launch { GameFilesPreferences.setPreference(context, GameFilesPreferences.SUPPORTED_LANGUAGES, defaultLanguages) }
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
            Text(stringResource(R.string.select_language), color = White)
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
                                        GameFilesPreferences.setPreference(context, GameFilesPreferences.LANGUAGE_KEY, language)
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
    val context = LocalContext.current
    val codeGroupOption by GameFilesPreferences.getPreference(context, GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = customColor)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.select_game), color = White)
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
                                        GameFilesPreferences.setPreference(context, GameFilesPreferences.CODE_GROUP_KEY, game)
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
    }
}

@Composable
fun AutoMouseModeOptionSelector() {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val autoMouseMode by GameFilesPreferences.getPreference(context, GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid").collectAsState(initial = "Hybrid")

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
                    text = stringResource(R.string.mouse_mode),
                    color = White
                )
                Text(
                    text = stringResource(R.string.mouse_mode_feat),
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
                        text = { Text(stringResource(R.string.none), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(context, GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "None")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hybrid), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(context, GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid")
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
        Text(stringResource(R.string.select_charset), modifier = Modifier.weight(1f))
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

                        feedbackMessage = "${stringRes(R.string.charset_changed_to)} $charset"
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
    val configFilePath = Constants.OPENMW_CFG
    val file = File(configFilePath)
    var encodingLine = file.readLines().find { it.startsWith("encoding=") }
    if (encodingLine == null) {
        encodingLine = "encoding=win1252"
        file.appendText("\n$encodingLine")
    }
    return encodingLine.split("=")[1]
}

fun updateCharset(newEncoding: String) {
    val configFilePath = Constants.OPENMW_CFG
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
    val context = LocalContext.current
    val backgroundAnimation by GameFilesPreferences.getPreference(context, GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground").collectAsState(initial = "BouncingBackground")
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
            Text(stringResource(R.string.background_animations), color = White)
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
                        text = { Text(stringResource(R.string.none), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(context, GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "None")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bouncing_background), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(context, GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rotating_image_background), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(context, GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "RotatingImageBackground")
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            expanded = false
                        })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.circular_background), color = White) },
                        onClick = {
                            scope.launch {
                                try {
                                    GameFilesPreferences.setPreference(context, GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "CircularBackground")
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
    val context = LocalContext.current
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
        Text(stringResource(R.string.configure_controls), color = White)
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
        Text(stringResource(R.string.reset_controls), color = White)
    }
    HorizontalDivider(color = White, thickness = 1.dp)

    if (showDialog2) {
        AlertDialog(
            onDismissRequest = {
                showDialog2 = false
            },
            title = {
                Text(text = stringResource(R.string.confirm_reset), color = White)
            },
            text = {
                Text(
                    stringResource(R.string.reset_the_controls_and_icons_tip),
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
                    Text(stringResource(R.string.btn_confirm), color = White)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showDialog2 = false
                    }, colors = ButtonDefaults.buttonColors(Color.Transparent)
                ) {
                    Text(stringResource(R.string.btn_cancel), color = White)
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
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val shapes = listOf("CircleShape", "RoundedCornerShape", "RectangleShape", "StadiumShape")
    val currentShape by GameFilesPreferences.getPreference(context, GameFilesPreferences.BUTTON_SHAPE, "CircleShape").collectAsState(initial = "StadiumShape")
    var feedbackMessage by remember { mutableStateOf("") }
    val buttonShape = stringToShape(currentShape)

    Row(
        modifier = Modifier
            .padding(16.dp)
            .background(color = customColor)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.select_button_shape), color = White, modifier = Modifier.weight(1f))
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
            Text(stringResource(R.string.shape), color = White)
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
                        feedbackMessage = String.format(stringRes(R.string.button_shape_changed_to_s), shape)
                    }
                )
            }
        }
    }

    if (feedbackMessage.isNotEmpty()) {
        Text(feedbackMessage, color = Color.Green, modifier = Modifier.padding(8.dp))
    }
}
