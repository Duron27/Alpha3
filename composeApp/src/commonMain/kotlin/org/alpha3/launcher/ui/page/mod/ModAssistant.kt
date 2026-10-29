package org.alpha3.launcher.ui.page.mod

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import org.alpha3.launcher.utils.BackHandler
import org.alpha3.launcher.utils.KramConversion
import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.utils.SSOHome
import kotlinx.coroutines.IO
import org.alpha3.launcher.resources.above_mod
import org.alpha3.launcher.resources.are_you_sure_you_want_to_delete_this_mod
import org.alpha3.launcher.resources.btn_cancel
import org.alpha3.launcher.resources.btn_confirm
import org.alpha3.launcher.resources.category
import org.alpha3.launcher.resources.choose_an_action_for_the_selected_mod
import org.alpha3.launcher.resources.confirm_action
import org.alpha3.launcher.resources.confirm_deletion
import org.alpha3.launcher.resources.confirm_reset
import org.alpha3.launcher.resources.content
import org.alpha3.launcher.resources.controls
import org.alpha3.launcher.resources.delete
import org.alpha3.launcher.resources.developer_menu
import org.alpha3.launcher.resources.failed_to_save_openmw_config
import org.alpha3.launcher.resources.groundcover
import org.alpha3.launcher.resources.mod_tools
import org.alpha3.launcher.resources.launcher_settings
import org.alpha3.launcher.resources.load_order_s
import org.alpha3.launcher.resources.mod_downloader
import org.alpha3.launcher.resources.mod_mover
import org.alpha3.launcher.resources.move
import org.alpha3.launcher.resources.name
import org.alpha3.launcher.resources.reset_the_settings_tip
import org.alpha3.launcher.resources.search
import org.alpha3.launcher.resources.search_mods
import org.alpha3.launcher.resources.select_the_new_position_for_the_mod
import org.alpha3.launcher.resources.selected_mod
import org.alpha3.launcher.resources.settings_cfg
import org.alpha3.launcher.resources.settings_file_reset
import org.alpha3.launcher.resources.switch_the_category_to_tip
import org.alpha3.launcher.resources.switch_to
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.utils.PlatformContext
import org.alpha3.launcher.utils.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.LocalPlatformContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.composables.core.ScrollArea
import com.composables.core.Thumb
import com.composables.core.ThumbVisibility
import com.composables.core.VerticalScrollbar
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.OpenMWPaths.SETTINGS_FILE
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.ui.controls.UIStateManager.transparentBlack
import org.alpha3.launcher.ui.navigation.LocalModAssistantViewModel
import org.alpha3.launcher.ui.page.setting.ControlsInsert
import org.alpha3.launcher.ui.page.setting.DevInsert
import org.alpha3.launcher.ui.page.setting.FeaturesSwitches
import org.alpha3.launcher.ui.view.BarOptions
import org.alpha3.launcher.utils.FileBrowserMode
import org.alpha3.launcher.utils.FileBrowserPopup
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.IniSettings
import org.alpha3.launcher.utils.MToast
import org.alpha3.launcher.utils.Terminal
import org.alpha3.launcher.utils.TranslateText
import org.alpha3.launcher.utils.UserManageAssets
import org.alpha3.launcher.ui.page.help.HelpScreen
import org.alpha3.launcher.utils.getLayoutType
import org.alpha3.launcher.utils.stringRes
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import org.alpha3.launcher.io.File
import org.alpha3.launcher.tools.moddev.ModDevOps
import org.alpha3.launcher.tools.moddev.ModDevToolsDialog
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class, InternalCoroutinesApi::class)
@Composable
private fun ModTabRow(
    viewModel: ModAssistantViewModel = LocalModAssistantViewModel.current
) {
    val context = LocalPlatformContext.current

    var selectedTabIndex by viewModel.selectedTabIndex
    val categories = viewModel.categories
    var showMods by viewModel.showMods
    var showNav by viewModel.showNav
    var showModTools by viewModel.showModTools
    var showHelp by viewModel.showHelp
    var settingsTab by viewModel.settingsTab
    var autoMods by viewModel.autoMods
    var downloader by viewModel.downloader
    var terminal by viewModel.terminal
    var kram by viewModel.kram
    var showDialogSettings by viewModel.showDialogSettings
    var showSearchDialog by viewModel.showSearchDialog
    var launchFloatingActionButton by viewModel.launchFloatingActionButton

    val newFeatureEnabledChecked by viewModel.newFeatureEnabledChecked.collectAsState(initial = false)

    val layoutType = getLayoutType()

    fun resetStates() {
        autoMods = false
        downloader = false
        showMods = false
        showNav = false
        terminal = false
        kram = false
        showModTools = false
        showHelp = false
        settingsTab = -1
        showDialogSettings = false
        showSearchDialog = false
        launchFloatingActionButton = false
    }

    // Rail layouts have room for the settings pages as tabs of their own.
    val settingsTabs = if (layoutType != NavigationSuiteType.NavigationBar)
        SettingsTab.entries.filter { it != SettingsTab.DEVELOPER || newFeatureEnabledChecked } else emptyList()

    // The id of each tab that is shown, in order. selectedTabIndex holds an id, not a position, so
    // the row's own selection has to be looked up or the indicator lands on the wrong tab.
    val tabIds = buildList {
        categories.indices.forEach { add(it) }
        if (Platform.features.supportsModDownloader) add(4)
        if (Platform.features.supportsModTools) add(5)
        settingsTabs.forEach { add(SETTINGS_TAB_BASE + it.ordinal) }
        add(7)
        add(HELP_TAB)
        if (newFeatureEnabledChecked) {
            if (Platform.features.supportsTerminal) add(8)
            if (Platform.features.supportsKram) add(9)
        }
    }

    // A rotation that takes the rail away must not leave a settings tab showing.
    LaunchedEffect(settingsTabs.isEmpty()) {
        if (settingsTabs.isEmpty() && selectedTabIndex >= SETTINGS_TAB_BASE) {
            resetStates()
            showMods = true
            selectedTabIndex = 0
        }
    }

    val inSettingsGroup = selectedTabIndex in SETTINGS_TAB_BASE until SETTINGS_TAB_BASE + SettingsTab.entries.size
    var lastModsTab by remember { mutableStateOf(0) }
    if (!inSettingsGroup && selectedTabIndex in categories.indices) lastModsTab = selectedTabIndex

    fun openCategory(index: Int) {
        resetStates()
        showMods = true
        selectedTabIndex = index
    }
    fun openDownloader() {
        if (isNetworkAvailable(context)) {
            resetStates()
            downloader = true
            selectedTabIndex = 4
        } else {
            MToast("No internet connection available")
        }
    }
    fun openModTools() {
        resetStates()
        showModTools = true
        selectedTabIndex = 5
    }
    fun openSettings(tab: SettingsTab) {
        resetStates()
        settingsTab = tab.ordinal
        selectedTabIndex = SETTINGS_TAB_BASE + tab.ordinal
    }

    Row(
        modifier = Modifier
            .drawBehind {
                drawLine(goldLine, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (settingsTabs.isNotEmpty()) {
            Row(
                modifier = Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(50)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                NavPill(selected = !inSettingsGroup, icon = Icons.Outlined.Folder, label = "Mods", strong = true) {
                    if (inSettingsGroup) openCategory(lastModsTab)
                }
                NavPill(selected = inSettingsGroup, icon = Icons.Outlined.Settings, label = "Settings", strong = true) {
                    if (!inSettingsGroup) openSettings(settingsTabs.first())
                }
            }
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (inSettingsGroup) {
                settingsTabs.forEach { tab ->
                    NavPill(selectedTabIndex == SETTINGS_TAB_BASE + tab.ordinal, tab.icon(), tab.title()) { openSettings(tab) }
                }
            } else {
                categories.forEachIndexed { index, category ->
                    NavPill(selectedTabIndex == index, categoryIcon(index), category.tag) { openCategory(index) }
                }
                if (Platform.features.supportsModDownloader) {
                    NavPill(selectedTabIndex == 4, Icons.Outlined.CloudDownload, stringResource(Res.string.mod_downloader)) { openDownloader() }
                }
                if (Platform.features.supportsModTools) {
                    NavPill(selectedTabIndex == 5, Icons.Outlined.Build, stringResource(Res.string.mod_tools)) { openModTools() }
                }
                // Without a rail the settings pages live on the side navigation, so they are not tabs here.
            }
        }
        NavPill(
            selected = selectedTabIndex == 7,
            icon = Icons.Default.Search,
            label = null,
            description = stringResource(Res.string.search_mods)
        ) {
            resetStates()
            showSearchDialog = true
            selectedTabIndex = 7
        }
        NavPill(
            selected = selectedTabIndex == HELP_TAB,
            icon = Icons.Outlined.HelpOutline,
            label = null,
            description = "Help"
        ) {
            resetStates()
            showHelp = true
            selectedTabIndex = HELP_TAB
        }
        if (newFeatureEnabledChecked) {
            if (Platform.features.supportsTerminal) NavPill(selectedTabIndex == 8, Icons.Outlined.Terminal, label = null, description = "Terminal") {
                resetStates()
                terminal = true
                selectedTabIndex = 8
            }
            if (Platform.features.supportsKram) NavPill(selectedTabIndex == 9, Icons.Outlined.Memory, label = null, description = "Kram") {
                resetStates()
                kram = true
                selectedTabIndex = 9
            }
        }
        if (layoutType != NavigationSuiteType.NavigationBar) {
            BarOptions(context)
        }
    }
}

private val goldLine = Color(202, 165, 96).copy(alpha = 0.45f)

private fun categoryIcon(index: Int): ImageVector = when (index) {
    0 -> Icons.Outlined.Folder
    1 -> Icons.Outlined.Extension
    2 -> Icons.Outlined.Grass
    else -> Icons.Outlined.Inventory2
}

private fun SettingsTab.icon(): ImageVector = when (this) {
    SettingsTab.LAUNCHER -> Icons.Outlined.Tune
    SettingsTab.CFG -> Icons.Outlined.Description
    SettingsTab.CONTROLS -> Icons.Outlined.SportsEsports
    SettingsTab.DEVELOPER -> Icons.Outlined.Code
}

/** A tab of the main bar: a gold pill with an icon and label when selected, dim otherwise. */
@Composable
private fun NavPill(
    selected: Boolean,
    icon: ImageVector,
    label: String?,
    strong: Boolean = false,
    description: String? = label,
    onClick: () -> Unit
) {
    val gold = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(50)
    val tint = if (selected) gold else Color.White.copy(alpha = 0.7f)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) gold.copy(alpha = if (strong) 0.28f else 0.18f) else Color.Transparent, shape)
            .border(1.dp, if (selected && !strong) gold.copy(alpha = 0.7f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (label == null) 10.dp else 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
        if (label != null) Text(label, color = tint, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@InternalCoroutinesApi
@OptIn(ExperimentalMaterial3Api::class)
@DelicateCoroutinesApi
@ExperimentalFoundationApi
@Composable
fun ModValuesList(
    modValues: List<ModValue>,
    viewModel: ModAssistantViewModel = LocalModAssistantViewModel.current
) {
    val context = LocalPlatformContext.current
    var selectedTabIndex by viewModel.selectedTabIndex
    var showFileBrowser by viewModel.showFileBrowser

    var selectedModPath by remember { mutableStateOf<String?>(null) }
    val categories = viewModel.categories
    var categorizedModValues by remember {
        mutableStateOf(categories.map { category ->
            modValues.filter { it.category == category.key }
        })
    }
    val lazyListState = rememberLazyListState()
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<ModValue>()) }
    val stateSB = com.composeunstyled.rememberScrollAreaState(lazyListState)
    var expanded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    val settingsFile = File(SETTINGS_FILE)

    val savedPath by GameFilesPreferences.getNullablePreference(GameFilesPreferences.GAME_FILES_URI_KEY).collectAsState(initial = null)

    // Manage what's in the tabs here
    var showMods by viewModel.showMods
    var showNav by viewModel.showNav
    var showModTools by viewModel.showModTools
    val showHelp by viewModel.showHelp
    val settingsTab by viewModel.settingsTab
    var downloader by viewModel.downloader
    var terminal by viewModel.terminal
    var kram by viewModel.kram
    var showDialogSettings by viewModel.showDialogSettings

    var showSearchDialog by viewModel.showSearchDialog

    val coroutineScope = rememberCoroutineScope()
    var highlightedCardId by remember { mutableStateOf<Int?>(null) }
    val translationChecked by GameFilesPreferences.getPreference(GameFilesPreferences.TRANSLATION_ENABLED_KEY, false).collectAsState(initial = false)
    val newFeatureEnabledChecked by viewModel.newFeatureEnabledChecked.collectAsState(initial = false)

    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val currentList = categorizedModValues[selectedTabIndex].toMutableList()
        val movedItem = currentList.removeAt(from.index)
        currentList.add(to.index, movedItem)

        categorizedModValues = categorizedModValues.toMutableList().apply {
            this[selectedTabIndex] = currentList
        }
    }

    LaunchedEffect(Unit) {
        ModUIState.refreshTrigger.collect {
            categorizedModValues = ModValue.updateUIFromModValues(categories.map { it.key })
        }
    }

    // Reload the mod values and update the UI
    categorizedModValues = ModValue.updateUIFromModValues(categories.map { it.key })

    Column {
        Row(
            modifier = Modifier
                .background(transparentBlack),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModTabRow()
        }

        AnimatedVisibility(
            visible = showDialog,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(Res.string.confirm_reset)) },
                text = { Text(stringResource(Res.string.reset_the_settings_tip)) },
                confirmButton = {
                    TextButton(
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
                    TextButton(
                        onClick = { showDialog = false }
                    ) {
                        Text(stringRes(Res.string.btn_cancel))
                    }
                }
            )
        }
        BackHandler(showFileBrowser) {
            showFileBrowser = false
        }
        AnimatedVisibility(
            visible = showFileBrowser,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            FileBrowserPopup(
                onDismiss = { showFileBrowser = false },
                onFolderSelected = { folder ->
                    viewModel.modPathSelection(folder) { modPath ->
                        selectedModPath = modPath
                        Log.d("Add_Data", "Add_Data: $modPath")
                    }
                    showFileBrowser = false
                },
                mode = FileBrowserMode.FOLDER
            )
        }
        fun handleSearchResult(modValue: ModValue) {
            viewModel.navigateToMod(
                modValue = modValue,
                categorizedModValues = categorizedModValues,
                setSelectedTabIndex = { selectedTabIndex = it },
                lazyListState = lazyListState,
                coroutineScope = coroutineScope,
                setHighlightedCardId = { id -> highlightedCardId = id }  // Pass the state setter
            )
            showSearchDialog = false
            showMods = true
        }
        AnimatedVisibility(
            visible = showSearchDialog,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize(),  // Increased height for filter options
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringRes(Res.string.search_mods), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        // Always include selectedCategory in the search
                        searchResults = viewModel.searchMods(it, modValues, selectedCategory)
                    },
                    label = { Text(stringResource(Res.string.search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Category Filter Chips (Horizontally Scrollable)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAllChecked = selectedCategory == null
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isAllChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = {
                            selectedCategory = null
                            searchResults = viewModel.searchMods(searchQuery, modValues, null)
                        }
                    ) {
                        Text(
                            text = "All",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (isAllChecked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }

                    categories.forEach { category ->
                        val isChecked = selectedCategory == category.key
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = {
                                selectedCategory = if (isChecked) null else category.key
                                searchResults = viewModel.searchMods(searchQuery, modValues, selectedCategory)
                            }
                        ) {
                            Text(
                                text = category.tag,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (isChecked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(color = Color.Black)
                ) {
                    items(searchResults, key = { it.id }) { result ->
                        Text(
                            text = result.value,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    handleSearchResult(result)
                                }
                                .padding(12.dp),
                            color = Color.White
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showNav,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            BarOptions(context)
        }
        AnimatedVisibility(
            visible = terminal,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Terminal()
        }
        AnimatedVisibility(
            visible = downloader && isNetworkAvailable(context),
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            SSOHome()
        }
        AnimatedVisibility(
            visible = kram,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            KramConversion(context)
        }
        AnimatedVisibility(
            visible = showModTools,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            ModToolsScreen()
        }
        AnimatedVisibility(
            visible = showHelp,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            HelpScreen()
        }
        AnimatedVisibility(
            visible = settingsTab >= 0,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            SettingsTabBody(SettingsTab.entries[settingsTab.coerceIn(0, SettingsTab.entries.lastIndex)])
        }
        // Show dialog when showDialog is true
        if (showDialogSettings) {
            LandscapeSettings(newFeatureEnabledChecked)
        } else {
            // Update UI after dialog closes
            categorizedModValues = ModValue.updateUIFromModValues(categories.map { it.key })

        }

        ScrollArea(state = stateSB) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                state = lazyListState,
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showMods) {
                    items(
                        categorizedModValues[selectedTabIndex],
                        key = { it.stableId }) { modValue ->
                        ReorderableItem(reorderableLazyListState, key = modValue.stableId) { isDragging ->
//                            var isDragging by remember { mutableStateOf(false) }
                            var showPopup by remember { mutableStateOf(false) }
                            var showDialog2 by remember { mutableStateOf(false) }
                            val isChecked = modValue.isChecked
                            var showDeleteConfirmation by remember { mutableStateOf(false) }
                            var showMoveDialog by remember { mutableStateOf(false) }
                            var showDevTools by remember { mutableStateOf(false) }
                            val isHighlighted = modValue.id == highlightedCardId
                            val haptics = LocalHapticFeedback.current
                            val backgroundColor by animateColorAsState(
                                when {
                                    isHighlighted -> Color(0xFF4CAF50)
                                    isDragging -> Color(0xFF8BC34A) // Color during drag
                                    modValue.isChecked -> Color.DarkGray
                                    else -> Color.DarkGray.copy(alpha = 0.5f)
                                }, label = ""
                            )
                            val scaling by animateFloatAsState(if (isDragging) 1.05f else 1f)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = backgroundColor,
                                ),
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = scaling
                                        scaleY = scaling
                                    },
                                elevation = CardDefaults.cardElevation(
                                    if (isDragging) 0.dp else 0.dp // fix transparent shadow bug
                                ),
                                onClick = { showPopup = true },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->

                                            // Update in-memory state
                                            val currentList = categorizedModValues[selectedTabIndex].toMutableList()
                                            val index = currentList.indexOfFirst { it.id == modValue.id }
                                            if (index != -1) {
                                                currentList[index] = currentList[index].copy(isChecked = checked)
                                                categorizedModValues = categorizedModValues.toMutableList().apply {
                                                    this[selectedTabIndex] = currentList
                                                }
                                            }

                                            // Directly modify just this line in the file
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val file = File(OpenMWPaths.USER_OPENMW_CFG)
                                                if (file.exists()) {
                                                    val associatedPlugins = mutableSetOf<String>()
                                                    if (modValue.category == "data") {
                                                        val dataDir = File(modValue.value)
                                                        val extensions = arrayOf("bsa", "esm", "esp", "esl", "omwaddon", "omwgame", "omwscripts")
                                                        fun getMatchingFiles(dir: File, set: MutableSet<String>) {
                                                            dir.listFiles()?.forEach { f ->
                                                                if (f.isFile) {
                                                                    if (f.extension.lowercase() in extensions) {
                                                                        set.add(f.name)
                                                                    }
                                                                } else if (f.isDirectory) {
                                                                    getMatchingFiles(f, set)
                                                                }
                                                            }
                                                        }
                                                        if (dataDir.exists() && dataDir.isDirectory) {
                                                            getMatchingFiles(dataDir, associatedPlugins)
                                                        }
                                                    }

                                                    val targetDataPath = modValue.value.trim().removeSurrounding("\"")

                                                    val lines = file.readLines()
                                                    val newLines = lines.map { line ->
                                                        val trimmed = line.trim()
                                                        val cleanLine = trimmed.removePrefix(";")
                                                        val cleanLineLower = cleanLine.lowercase()

                                                        val isThisDataLine = modValue.category == "data" && cleanLineLower.startsWith("data=") &&
                                                            cleanLine.substringAfter("=").trim().removeSurrounding("\"").trim() == targetDataPath

                                                        when {
                                                            isThisDataLine -> {
                                                                if (!checked) {
                                                                    if (!trimmed.startsWith(";")) ";$cleanLine" else line
                                                                } else {
                                                                    if (cleanLine.startsWith("data=")) cleanLine else "data=\"$targetDataPath\""
                                                                }
                                                            }
                                                            modValue.category == "data" && associatedPlugins.isNotEmpty() && cleanLine.contains("=") -> {
                                                                val keyPart = cleanLine.substringBefore("=").trim().lowercase()
                                                                val valPart = cleanLine.substringAfter("=").trim().removeSurrounding("\"").trim()
                                                                if (keyPart in setOf("content", "groundcover", "fallback-archive") &&
                                                                    associatedPlugins.any { it.equals(valPart, ignoreCase = true) }) {
                                                                    if (!checked) {
                                                                        if (!trimmed.startsWith(";")) ";$cleanLine" else line
                                                                    } else {
                                                                        cleanLine
                                                                    }
                                                                } else {
                                                                    line
                                                                }
                                                            }
                                                            trimmed == "${modValue.category}=${modValue.value}" && !checked ->
                                                                ";${modValue.category}=${modValue.value}"
                                                            trimmed == ";${modValue.category}=${modValue.value}" && checked ->
                                                                "${modValue.category}=${modValue.value}"
                                                            else -> line
                                                        }
                                                    }
                                                    file.writeText(newLines.joinToString("\n"))
                                                    withContext(Dispatchers.Main) {
                                                        categorizedModValues = ModValue.updateUIFromModValues(categories.map { it.key })
                                                    }
                                                }
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color.Transparent,
                                            uncheckedColor = Color.Red.copy(alpha = 0.5f),
                                            checkmarkColor = Color.Green,
                                            disabledCheckedColor = Color.Red,
                                            disabledUncheckedColor = Color.Red
                                        )
                                    )
                                    Column(modifier = Modifier
                                        .padding(4.dp)
                                        .weight(1f)) {
                                        var translatedText by remember { mutableStateOf("") }
                                        if (translationChecked) {
                                            TranslateText(
                                                inputText = modValue.value,
                                                onTranslationResult = { result ->
                                                    translatedText =
                                                        result
                                                }
                                            )
                                        }
                                        Text(
                                            text = if (translationChecked) translatedText else modValue.value.replace("/storage/emulated/0/", ""),
                                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                        )
                                        Text(
                                            text = stringResource(Res.string.load_order_s, modValue.id.toString()),
                                            style = MaterialTheme.typography.bodySmall.copy(color = Color.White)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier,
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        IconButton(
                                            modifier = Modifier.draggableHandle(
                                                onDragStarted = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragStopped = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                                                    val currentList = categorizedModValues[selectedTabIndex].toMutableList()

                                                    // Update IDs based on new order
                                                    currentList.forEachIndexed { index, item ->
                                                        currentList[index] = item.copy(id = index + 1)
                                                    }

                                                    categorizedModValues = categorizedModValues.toMutableList().apply {
                                                        this[selectedTabIndex] = currentList
                                                    }

                                                    // Get the category being modified (e.g., "data", "content", "groundcover")
                                                    val modifiedCategory = categories[selectedTabIndex]

                                                    // Write ONLY the modified category's section
                                                    coroutineScope.launch {
                                                        viewModel.writeModValuesToFile(
                                                            modValues = categorizedModValues.flatten(),
                                                            filePath = OpenMWPaths.USER_OPENMW_CFG,
                                                            targetCategory = modifiedCategory.key,  // This ensures only that section is updated
                                                            onFinish = { isSuccess ->
                                                                if (!isSuccess) {
                                                                    MToast(stringRes(Res.string.failed_to_save_openmw_config))
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            ),
                                            onClick = { showDialog2 = true },
                                        ) {
                                            Icon(Icons.Rounded.Menu, contentDescription = "Reorder")
                                        }
                                    }
                                }
                                if (showPopup) {
                                    Popup(
                                        alignment = Alignment.Center,
                                        onDismissRequest = {
                                            showPopup = false
                                        } // Hide the popup when dismissed
                                    ) {
                                        Surface(
                                            modifier = Modifier.padding(16.dp),
                                            shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.background
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = stringResource(Res.string.choose_an_action_for_the_selected_mod),
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = "${stringResource(Res.string.name)}: ${modValue.value}",
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                )
                                                Text(
                                                    text = "${stringResource(Res.string.category)}: ${modValue.category}",
                                                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                if (modValue.category != "data" && modValue.category != "fallback-archive") {
                                                    Button(
                                                        onClick = {
                                                            // Handle the switch category action
                                                            val updatedList =
                                                                categorizedModValues[selectedTabIndex].toMutableList()
                                                            val index =
                                                                updatedList.indexOfFirst { it.id == modValue.id }
                                                            if (index != -1) {
                                                                val newCategory =
                                                                    if (modValue.category == "content") "groundcover" else "content"
                                                                updatedList[index] =
                                                                    updatedList[index].copy(category = newCategory)
                                                                categorizedModValues =
                                                                    categorizedModValues.toMutableList()
                                                                        .apply {
                                                                            this[selectedTabIndex] =
                                                                                updatedList
                                                                        }

                                                                // Update the file by removing the matching line and appending to the correct category
                                                                val file =
                                                                    File(OpenMWPaths.USER_OPENMW_CFG)
                                                                val updatedLines =
                                                                    mutableListOf<String>()
                                                                if (file.exists()) {
                                                                    file.forEachLine { line ->
                                                                        // Skip the line that matches the modValue to remove it
                                                                        if (line.trim() != ";${modValue.category}=${modValue.value}" && line.trim() != "${modValue.category}=${modValue.value}") {
                                                                            updatedLines.add(line)
                                                                        }
                                                                    }
                                                                }

                                                                // Find the last line with the new category to append after it
                                                                val newLine =
                                                                    "${newCategory}=${modValue.value}"
                                                                val lastCategoryIndex =
                                                                    updatedLines.indexOfLast {
                                                                        it.trim()
                                                                            .startsWith("$newCategory=")
                                                                    }
                                                                val appendIndex =
                                                                    if (lastCategoryIndex != -1) {
                                                                        // Append after the last line of the new category
                                                                        lastCategoryIndex + 1
                                                                    } else {
                                                                        // If no lines exist for the new category, append at the end
                                                                        updatedLines.size
                                                                    }

                                                                // Insert the new line at the determined index
                                                                updatedLines.add(
                                                                    appendIndex,
                                                                    newLine
                                                                )

                                                                // Write the updated content back to the file
                                                                file.writeText(
                                                                    updatedLines.joinToString(
                                                                        "\n"
                                                                    ) + if (updatedLines.lastOrNull()
                                                                            ?.isNotEmpty() == true
                                                                    ) "\n" else ""
                                                                )

                                                                // Reload the mod values and update the UI
                                                                categorizedModValues =
                                                                    ModValue.updateUIFromModValues(
                                                                        categories.map { it.key })
                                                            }
                                                            showPopup = false
                                                        },
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = Color.DarkGray
                                                        )
                                                    ) {
                                                        Text(

                                                            text = "${stringResource(Res.string.switch_to)} ${
                                                                if (modValue.category == "content")
                                                                    stringResource(Res.string.groundcover)
                                                                else
                                                                    stringResource(Res.string.content)
                                                            }",
                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                color = Color.White
                                                            )
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))

                                                Button(
                                                    onClick = {
                                                        // Show the delete confirmation dialog
                                                        showDeleteConfirmation = true
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                                ) {
                                                    Text(
                                                        text = stringResource(Res.string.delete),
                                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Button(
                                                    onClick = {
                                                        // Show the move dialog
                                                        showMoveDialog = true
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                                ) {
                                                    Text(
                                                        text = stringResource(Res.string.move),
                                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                    )
                                                }

                                                if (newFeatureEnabledChecked) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Button(
                                                        onClick = { showPopup = false; showDevTools = true },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A))
                                                    ) {
                                                        Text(
                                                            text = "Developer tools",
                                                            style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Button(
                                                    onClick = { showPopup = false },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                                ) {
                                                    Text(
                                                        text = stringRes(Res.string.btn_cancel),
                                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (showDevTools) {
                                    val devFs = Platform.features.toolFs
                                    val devPlatform = Platform.features.toolPlatform
                                    if (devFs != null && devPlatform != null) {
                                        val devCfg = File(OpenMWPaths.USER_OPENMW_CFG)
                                        ModDevToolsDialog(
                                            category = modValue.category,
                                            value = modValue.value,
                                            pluginFile = if (modValue.category == "content" || modValue.category == "groundcover" || modValue.category == "fallback-archive") ModDevOps(devFs, devPlatform).findPlugin(devCfg, modValue.value) else null,
                                            cfgFile = devCfg,
                                            fs = devFs,
                                            platform = devPlatform,
                                            onDismiss = { showDevTools = false },
                                        )
                                    } else showDevTools = false
                                }

                                if (showDeleteConfirmation) {
                                    var associatedPluginsForDelete by remember { mutableStateOf(emptySet<String>()) }
                                    LaunchedEffect(showDeleteConfirmation, modValue) {
                                        if (showDeleteConfirmation && modValue.category == "data") {
                                            withContext(Dispatchers.IO) {
                                                val set = mutableSetOf<String>()
                                                val dataDir = File(modValue.value.removeSurrounding("\"").trim())
                                                val extensions = arrayOf("bsa", "esm", "esp", "esl", "omwaddon", "omwgame", "omwscripts")
                                                fun getMatchingFiles(dir: File, set: MutableSet<String>) {
                                                    dir.listFiles()?.forEach { f ->
                                                        if (f.isFile) {
                                                            if (f.extension.lowercase() in extensions) {
                                                                set.add(f.name)
                                                            }
                                                        } else if (f.isDirectory) {
                                                            getMatchingFiles(f, set)
                                                        }
                                                    }
                                                }
                                                if (dataDir.exists() && dataDir.isDirectory) {
                                                    getMatchingFiles(dataDir, set)
                                                }
                                                associatedPluginsForDelete = set
                                            }
                                        } else {
                                            associatedPluginsForDelete = emptySet()
                                        }
                                    }

                                    AlertDialog(
                                        onDismissRequest = { showDeleteConfirmation = false },
                                        title = { Text(stringResource(Res.string.confirm_deletion)) },
                                        text = { 
                                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                                Text(stringResource(Res.string.are_you_sure_you_want_to_delete_this_mod))
                                                if (associatedPluginsForDelete.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = "The following associated mods will also be removed from the configuration:",
                                                        color = Color.Red,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    associatedPluginsForDelete.forEach { plugin ->
                                                        Text(text = "- $plugin", color = Color.White, fontSize = 12.sp)
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        val file = File(OpenMWPaths.USER_OPENMW_CFG)
                                                        val updatedLines = mutableListOf<String>()
                                                        val pluginsToRemoveLowerCase = associatedPluginsForDelete.map { it.lowercase() }.toSet()
                                                        
                                                        if (file.exists()) {
                                                            file.forEachLine { line ->
                                                                val trimmed = line.trim()
                                                                val cleanLine = trimmed.removePrefix(";")
                                                                val isSelf = trimmed == ";${modValue.category}=${modValue.value}" || trimmed == "${modValue.category}=${modValue.value}"
                                                                
                                                                var isPlugin = false
                                                                if (modValue.category == "data" && cleanLine.contains("=")) {
                                                                    val keyPart = cleanLine.substringBefore("=").trim().lowercase()
                                                                    val valPart = cleanLine.substringAfter("=").trim().removeSurrounding("\"").trim()
                                                                    if (keyPart in setOf("content", "groundcover", "fallback-archive") && pluginsToRemoveLowerCase.contains(valPart.lowercase())) {
                                                                        isPlugin = true
                                                                    }
                                                                }
                                                                
                                                                if (!isSelf && !isPlugin) {
                                                                    updatedLines.add(line)
                                                                }
                                                            }
                                                        }
                                                        
                                                        // Write the updated content back to the file, ensuring no trailing empty line
                                                        file.writeText(updatedLines.joinToString("\n") + if (updatedLines.lastOrNull()?.isNotEmpty() == true) "\n" else "")
                                                        
                                                        withContext(Dispatchers.Main) {
                                                            ModUIState.refreshUI()
                                                            showDeleteConfirmation = false
                                                            showPopup = false
                                                        }
                                                    }
                                                }
                                            ) {
                                                Text(stringRes(Res.string.btn_confirm))
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showDeleteConfirmation = false }) {
                                                Text(stringRes(Res.string.btn_cancel))
                                            }
                                        }
                                    )
                                }
                                if (showMoveDialog) {
                                    // Get the initial index of the mod
                                    val initialIndex =
                                        categorizedModValues[selectedTabIndex].indexOfFirst { it.id == modValue.id }
                                    selectedIndex = initialIndex

                                    AlertDialog(
                                        onDismissRequest = { showMoveDialog = false },
                                        title = {
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = stringResource(Res.string.mod_mover),
                                                    color = Color.Green,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        },
                                        text = {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(stringResource(Res.string.select_the_new_position_for_the_mod))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {

                                                    PositionPicker(
                                                        count = categorizedModValues[selectedTabIndex].size,
                                                        selected = selectedIndex,
                                                        label = { index ->
                                                            val modValues = categorizedModValues[selectedTabIndex].getOrNull(index)?.value ?: ""
                                                            "$index = $modValues"
                                                        },
                                                        onSelected = { selectedIndex = it }
                                                    )

                                                }

                                                // Keep the current mod value as the one you clicked on
                                                val currentModValue = modValue.value

                                                // Display the mod value above the selected index
                                                val aboveModValue =
                                                    categorizedModValues[selectedTabIndex].getOrNull(
                                                        selectedIndex - 1
                                                    )?.value
                                                if (aboveModValue != null) {

                                                    Text("${stringResource(Res.string.above_mod)}: $aboveModValue")
                                                }
                                                Text(

                                                    text = "${stringResource(Res.string.selected_mod)}: $currentModValue",
                                                    color = Color.Green,
                                                    textDecoration = TextDecoration.Underline,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        },
                                        confirmButton = {
                                            val isSamePosition = selectedIndex == initialIndex
                                            TextButton(
                                                onClick = {
                                                    // Handle the move action if the positions are different
                                                    if (!isSamePosition) {
                                                        val updatedList = categorizedModValues[selectedTabIndex].toMutableList()
                                                        val currentIndex = updatedList.indexOfFirst { it.id == modValue.id }

                                                        if (currentIndex != -1) {
                                                            // Implement the logic to move the mod to the new position
                                                            val newPosition = selectedIndex
                                                            Log.d("MoveDialog", "Moving mod from $currentIndex to $newPosition")
                                                            val movedMod = updatedList.removeAt(currentIndex)
                                                            updatedList.add(newPosition, movedMod)

                                                            // Update id for all mods
                                                            updatedList.forEachIndexed { index, modValue ->
                                                                Log.d("MoveDialog", "Mod at index $index has id ${modValue.id}")
                                                            }

                                                            // Update the mod values
                                                            categorizedModValues = categorizedModValues.toMutableList().apply {
                                                                this[selectedTabIndex] = updatedList
                                                            }

                                                            // Update the file by removing the matching line and inserting at the new position
                                                            val file = File(OpenMWPaths.USER_OPENMW_CFG)
                                                            val updatedLines = mutableListOf<String>()
                                                            if (file.exists()) {
                                                                file.forEachLine { line ->
                                                                    // Skip the line that matches the modValue to remove it
                                                                    if (line.trim() != ";${modValue.category}=${modValue.value}" && line.trim() != "${modValue.category}=${modValue.value}") {
                                                                        updatedLines.add(line)
                                                                    }
                                                                }
                                                            }

                                                            // Find the insertion point for the new position among same-category lines
                                                            val categoryLines = updatedLines.withIndex().filter { it.value.trim().startsWith("${modValue.category}=") }
                                                            val insertIndex = if (categoryLines.isNotEmpty() && newPosition < categoryLines.size) {
                                                                // Insert after the line at newPosition for the same category
                                                                categoryLines[newPosition].index + 1
                                                            } else if (categoryLines.isNotEmpty()) {
                                                                // If newPosition is beyond the last same-category line, append after the last one
                                                                categoryLines.last().index + 1
                                                            } else {
                                                                // If no lines exist for the category, append at the end
                                                                updatedLines.size
                                                            }

                                                            // Insert the new line at the determined index
                                                            updatedLines.add(insertIndex, "${modValue.category}=${modValue.value}")

                                                            // Write the updated content back to the file
                                                            file.writeText(updatedLines.joinToString("\n") + if (updatedLines.lastOrNull()?.isNotEmpty() == true) "\n" else "")

                                                            // Reload the mod values and update the UI
                                                            categorizedModValues = ModValue.updateUIFromModValues(categories.map { it.key })
                                                            Log.d("MoveDialog", "Mod values reloaded")
                                                        } else {
                                                            Log.d("MoveDialog", "Mod with value not found.")
                                                        }
                                                        showMoveDialog = false
                                                        showPopup = false
                                                    } else {
                                                        Log.d("MoveDialog", "Selected index is the same as the current index.")
                                                    }
                                                },
                                                enabled = !isSamePosition
                                            ) {
                                                Text(stringRes(Res.string.move))
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showMoveDialog = false }) {
                                                Text(stringRes(Res.string.btn_cancel))
                                            }
                                        }
                                    )
                                }

                                if (showDialog2 && modValue.category != "data" && modValue.category != "fallback-archive") {
                                    AlertDialog(
                                        onDismissRequest = { showDialog2 = false },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    // Handle the switch category action
                                                    val updatedList = categorizedModValues[selectedTabIndex].toMutableList()
                                                    val index = updatedList.indexOfFirst { it.id == modValue.id }
                                                    if (index != -1) {
                                                        val newCategory = if (modValue.category == "content") "groundcover" else "content"
                                                        updatedList[index] = updatedList[index].copy(category = newCategory)
                                                        categorizedModValues = categorizedModValues.toMutableList().apply {
                                                            this[selectedTabIndex] = updatedList
                                                        }

                                                        // Update the file by removing the matching line and appending to the correct category
                                                        val file = File(OpenMWPaths.USER_OPENMW_CFG)
                                                        val updatedLines = mutableListOf<String>()
                                                        if (file.exists()) {
                                                            file.forEachLine { line ->
                                                                // Skip the line that matches the modValue to remove it
                                                                if (line.trim() != ";${modValue.category}=${modValue.value}" && line.trim() != "${modValue.category}=${modValue.value}") {
                                                                    updatedLines.add(line)
                                                                }
                                                            }
                                                        }

                                                        // Find the last line with the new category to append after it
                                                        val newLine = "${newCategory}=${modValue.value}"
                                                        val lastCategoryIndex = updatedLines.indexOfLast { it.trim().startsWith("$newCategory=") }
                                                        val appendIndex = if (lastCategoryIndex != -1) {
                                                            // Append after the last line of the new category
                                                            lastCategoryIndex + 1
                                                        } else {
                                                            // If no lines exist for the new category, append at the end
                                                            updatedLines.size
                                                        }

                                                        // Insert the new line at the determined index
                                                        updatedLines.add(appendIndex, newLine)

                                                        // Write the updated content back to the file
                                                        file.writeText(updatedLines.joinToString("\n") + if (updatedLines.lastOrNull()?.isNotEmpty() == true) "\n" else "")

                                                        // Reload the mod values and update the UI
                                                        categorizedModValues = ModValue.updateUIFromModValues(categories.map { it.key })
                                                    }
                                                    showDialog2 = false
                                                }
                                            ) {
                                                Text(stringRes(Res.string.btn_confirm))
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showDialog2 = false }) {
                                                Text(stringRes(Res.string.btn_cancel))
                                            }
                                        },
                                        title = { Text(stringResource(Res.string.confirm_action)) },
                                        text = { Text(stringResource(Res.string.switch_the_category_to_tip, if (modValue.category == "content") stringRes(
                                            Res.string.groundcover
                                        ) else stringRes(Res.string.content)
                                        )) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(10.dp)
            ) {
                Thumb(
                    modifier = Modifier.background(Color.Black.copy(0.3f), RoundedCornerShape(100)),
                    thumbVisibility = ThumbVisibility.HideWhileIdle(
                        enter = fadeIn(),
                        exit = fadeOut(),
                        hideDelay = 0.5.seconds
                    )
                )
            }
        }
    }
}

private fun isNetworkAvailable(context: PlatformContext): Boolean = Platform.features.hasInternetConnection()

/** Tab ids at and above this are [SettingsTab]s; below it they are the mod screen's own tabs. */
private const val SETTINGS_TAB_BASE = 10
private const val HELP_TAB = 20

/** The settings pages that a landscape layout shows as tabs. */
enum class SettingsTab {
    LAUNCHER, CFG, CONTROLS, DEVELOPER;

    @Composable
    fun title(): String = when (this) {
        LAUNCHER -> stringResource(Res.string.launcher_settings)
        CFG -> stringResource(Res.string.settings_cfg)
        CONTROLS -> stringResource(Res.string.controls)
        DEVELOPER -> stringResource(Res.string.developer_menu)
    }
}

@Composable
fun SettingsTabBody(tab: SettingsTab) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            when (tab) {
                SettingsTab.CFG -> IniSettings()
                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .background(color = customColor),
                ) {
                    when (tab) {
                        SettingsTab.LAUNCHER -> FeaturesSwitches()
                        SettingsTab.CONTROLS -> ControlsInsert()
                        else -> DevInsert()
                    }
                }
            }
        }
    }
}

/** The settings pages as a row of tabs above their content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandscapeSettings(isNewFeatureEnabledChecked: Boolean) {
    val tabs = SettingsTab.entries.filter { it != SettingsTab.DEVELOPER || isNewFeatureEnabledChecked }
    var selected by remember { mutableStateOf(SettingsTab.LAUNCHER) }
    if (selected !in tabs) selected = SettingsTab.LAUNCHER
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selected),
            edgePadding = 0.dp,
            containerColor = Color.Transparent,
            divider = {}
        ) {
            tabs.forEach { tab ->
                Tab(selected = tab == selected, onClick = { selected = tab }, text = { Text(tab.title()) })
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { SettingsTabBody(selected) }
    }
}


/** Scrollable list used to pick a new position; replaces the Android NumberPicker wheel. */
@Composable
private fun RowScope.PositionPicker(
    count: Int,
    selected: Int,
    label: (Int) -> String,
    onSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).heightIn(max = 180.dp)
    ) {
        items(count) { index ->
            Text(
                text = label(index),
                color = if (index == selected) Color.Green else Color.Unspecified,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(index) }
                    .padding(vertical = 6.dp, horizontal = 8.dp)
            )
        }
    }
}
