package org.alpha3.launcher.ui.view

import org.alpha3.launcher.resources.btn_cancel
import org.alpha3.launcher.resources.btn_confirm
import org.alpha3.launcher.resources.close
import org.alpha3.launcher.resources.confirm_reset
import org.alpha3.launcher.resources.dreadnought_big_004
import org.alpha3.launcher.resources.env_args
import org.alpha3.launcher.resources.generate_navmesh
import org.alpha3.launcher.resources.ic_launcher_foreground
import org.alpha3.launcher.resources.launcher_topbar_title
import org.alpha3.launcher.resources.morrowind_folder_not_found_tip
import org.alpha3.launcher.resources.regenerate_navmesh
import org.alpha3.launcher.resources.reset_resources
import org.alpha3.launcher.resources.reset_settings
import org.alpha3.launcher.resources.reset_the_resources_tip
import org.alpha3.launcher.resources.reset_the_settings_tip
import org.alpha3.launcher.resources.resources_reset
import org.alpha3.launcher.resources.settings_file_reset
import org.alpha3.launcher.resources.size
import org.alpha3.launcher.resources.stadia_controller_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.view_last_crash
import org.alpha3.launcher.launch.LaunchOptionsDialog
import org.alpha3.launcher.crash.CrashViewerDialog
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.utils.PlatformContext
import org.alpha3.launcher.utils.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.LocalPlatformContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.OpenMWPaths.SETTINGS_FILE
import org.alpha3.launcher.utils.runNavMeshSelected
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.controls.UIStateManager.editMode
import org.alpha3.launcher.ui.controls.UIStateManager.isAppLoggingEnabled
import org.alpha3.launcher.ui.controls.UIStateManager.isTabExpanded
import org.alpha3.launcher.ui.controls.UIStateManager.launchedActivity
import org.alpha3.launcher.ui.controls.UIStateManager.transparentBlack
import org.alpha3.launcher.ui.page.setting.CodeGroupOptionSelector
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.MToast
import org.alpha3.launcher.utils.UserManageAssets
import org.alpha3.launcher.utils.startGame
import org.alpha3.launcher.tools.MorrowindSetup
import org.alpha3.launcher.utils.stringRes
import org.alpha3.launcher.io.File

@InternalCoroutinesApi
@ExperimentalMaterial3Api
@Composable
fun MyTopBar(context: PlatformContext) {
    val controllerConnected = Engine.isControllerConnected()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = transparentBlack,
            scrolledContainerColor = Color.Unspecified,
            navigationIconContentColor = Color.Unspecified,
            titleContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = Color.Unspecified
        ),
        title = {
            Text(
                stringRes(Res.string.launcher_topbar_title),
                maxLines = 1,
                color = Color.White,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            if (controllerConnected) {
                Image(
                    painter = painterResource(Res.drawable.stadia_controller_24dp_e8eaed_fill0_wght400_grad0_opsz24),
                    contentDescription = "Icon",
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(Color.Green)
                )
            }
            BarOptions(context)
        },
        modifier = Modifier
    )
}

@Composable
fun NavmeshScreen(onComplete: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        ProgressWithNavmesh(onComplete = onComplete)
    }
}

@InternalCoroutinesApi
@ExperimentalMaterial3Api
@Composable
fun MyFloatingActionButton() {
    val context = LocalPlatformContext.current
    val savedPath by GameFilesPreferences.getNullablePreference(GameFilesPreferences.GAME_FILES_URI_KEY).collectAsState(initial = null)
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val bypassGameCheck by GameFilesPreferences.getPreference(GameFilesPreferences.BYPASS_GAME_FILES_KEY, false).collectAsState(initial = false)
    var isCopyingResources by remember { mutableStateOf(false) } // Track copying state
    val coroutineScope = rememberCoroutineScope()

    val image: Painter = when (codeGroupOption) {
        "OpenMW" -> painterResource(Res.drawable.ic_launcher_foreground)
        "UQM" -> painterResource(Res.drawable.dreadnought_big_004)
        else -> painterResource(Res.drawable.ic_launcher_foreground)
    }

    FloatingActionButton(
        onClick = {
            coroutineScope.launch {
                UIStateManager.configureControls = false
                launchedActivity = true
                if (codeGroupOption == "OpenMW") {
                    if (!bypassGameCheck) {
                        val uri = savedPath
                        if (uri != null) {
                            val morrowindEsm = File(uri, "Morrowind.esm")
                            val morrowindEsmFallback = File(uri, "/Data Files/Morrowind.esm")
                            if (morrowindEsm.exists() || morrowindEsmFallback.exists()) {
                                isCopyingResources = true // Start copying
                                withContext(Dispatchers.Default) {
                                    UserManageAssets(context).resourcePrepare()
                                    MorrowindSetup.ensureReady(savedGameFolder = savedPath)
                                }
                                isCopyingResources = false // Copying complete
                                editMode = false
                                isAppLoggingEnabled = false
                                context.startGame()
                            } else {
                                MToast(stringRes(Res.string.morrowind_folder_not_found_tip))
                            }
                        }
                    } else {
                        isCopyingResources = true // Start copying
                        withContext(Dispatchers.Default) {
                            UserManageAssets(context).resourcePrepare()
                            MorrowindSetup.ensureReady(savedGameFolder = savedPath)
                        }
                        isCopyingResources = false // Copying complete

                        isAppLoggingEnabled = false
                        context.startGame()

                    }
                } else  {
                    isAppLoggingEnabled = false
                    context.startGame()
                }
            }
        },
        containerColor = Color(alpha = 0.6f, red = 0f, green = 0f, blue = 0f),
        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
    ) {
        if (isCopyingResources) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp), // Adjust size as needed
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            Image(
                painter = image,
                contentDescription = "Launch"
            )
        }
    }
}

@InternalCoroutinesApi
@ExperimentalMaterial3Api
@Composable
fun BarOptions (context: PlatformContext) {
    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showResetResourcesDialog by remember { mutableStateOf(false) }
    val settingsFile = File(SETTINGS_FILE)
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val scope = rememberCoroutineScope()

    IconButton(onClick = { isTabExpanded = !isTabExpanded }) {
        Icon(
            imageVector = if (isTabExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle Tab",
            tint = Color.White
        )
    }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Filled.Menu,
            modifier = Modifier,
            contentDescription = "Localized description"
        )
    }

    DropdownMenu(
        modifier = Modifier
            .background(color = transparentBlack)
            .border(
                BorderStroke(width = 1.dp, color = Color.Black)
            ),
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        DropdownMenuItem(
            text = { Text(stringRes(Res.string.env_args), color = Color.White) },
            onClick = {
                expanded = false
                "".moeDialog(fullCustom = true) { dialog ->
                    LaunchOptionsDialog(onClose = { dialog.dismiss() })
                }
            }
        )
        DropdownMenuItem(
            text = {
                val current = org.alpha3.launcher.paths.Profiles.let { if (it.isDefault) "Default" else it.active }
                Text("Profile: $current", color = Color.White)
            },
            onClick = {
                expanded = false
                "".moeDialog(fullCustom = true) { dialog ->
                    ProfilesDialog(onClose = { dialog.dismiss() })
                }
            }
        )
        org.alpha3.launcher.utils.Platform.features.plugins?.let { plugins ->
            DropdownMenuItem(
                text = { Text("Plugins", color = Color.White) },
                onClick = {
                    expanded = false
                    "".moeDialog(fullCustom = true) { dialog ->
                        PluginsDialog(plugins, onClose = { dialog.dismiss() })
                    }
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringRes(Res.string.view_last_crash), color = Color.White) },
            onClick = {
                expanded = false
                "".moeDialog(fullCustom = true) { dialog ->
                    CrashViewerDialog(context, onClose = { dialog.dismiss() })
                }
            }
        )
        if (codeGroupOption == "OpenMW") {
            DropdownMenuItem(
                text = {
                    val navmeshFile = File("${OpenMWPaths.USER_DATA}/navmesh.db")
                    if (navmeshFile.exists()) {
                        val fileSize = navmeshFile.length()

                        Text("${stringResource(Res.string.regenerate_navmesh)} \n(${stringRes(Res.string.size)}: ${fileSize / 1024} KB)", color = Color.White) // Display size in KB
                    } else {
                        Text(stringResource(Res.string.generate_navmesh), color = Color.White)
                    }
                },
                onClick = {
                    // not the menu's scope: the menu leaves the screen (and its scope is cancelled) once useNavmesh is set
                    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    UIStateManager.useNavmesh = true
                    // Delete the navmesh file if it exists
                    val navmeshFile = File("${OpenMWPaths.USER_DATA}/navmesh.db")
                    if (navmeshFile.exists()) {
                        navmeshFile.delete()
                    }
                    withContext(Dispatchers.Default) {
                        UserManageAssets(context).resourcePrepare()
                    }
                    runNavMeshSelected(context)
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.reset_resources), color = Color.White) },
                onClick = {
                    showResetResourcesDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.reset_settings), color = Color.White) },
                onClick = {
                    showDialog = true
                }
            )
        }
        CodeGroupOptionSelector()
        if (showResetResourcesDialog) {
            AlertDialog(
                onDismissRequest = { showResetResourcesDialog = false },
                title = { Text(stringRes(Res.string.confirm_reset)) },
                text = { Text(stringRes(Res.string.reset_the_resources_tip)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetResourcesDialog = false
                            val progress = "".moeDialog(fullCustom = true) { ProgressDialog(text = stringRes(Res.string.reset_resources)) }
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    UserManageAssets(context).resetUserResources()
                                }
                                progress.dismiss()
                                MToast(stringRes(Res.string.resources_reset))
                            }
                            expanded = false
                        }
                    ) {
                        Text(stringRes(Res.string.btn_confirm))
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showResetResourcesDialog = false }
                    ) {
                        Text(stringRes(Res.string.btn_cancel))
                    }
                }
            )
        }
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringRes(Res.string.confirm_reset)) },
                text = { Text(stringRes(Res.string.reset_the_settings_tip)) },
                confirmButton = {
                    Button(
                        onClick = {
                            if (settingsFile.exists()) {
                                settingsFile.delete()
                                Log.d("ManageAssets", "Deleted existing file: $SETTINGS_FILE")
                                // Copy over settings.cfg
                                UserManageAssets(context).resetUserConfig()
                                expanded = false
                            }
                            MToast(stringRes(Res.string.settings_file_reset))
                            showDialog = false
                        }
                    ) {
                        Text(stringRes(Res.string.btn_confirm))
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showDialog = false }
                    ) {
                        Text(stringRes(Res.string.btn_cancel))
                    }
                }
            )
        }
    }
}
