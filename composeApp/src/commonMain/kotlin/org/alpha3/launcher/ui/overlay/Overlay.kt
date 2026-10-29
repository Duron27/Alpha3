@file:Suppress("DEPRECATION")

package org.alpha3.launcher.ui.overlay

import org.alpha3.launcher.ui.view.PlatformWebView
import org.alpha3.launcher.ui.view.WebController
import org.alpha3.launcher.utils.Platform
import org.jetbrains.compose.resources.painterResource
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.resources.add_delete_and_modify_all_buttons_tip
import org.alpha3.launcher.resources.click_the_spinning_yellow_dots_tip
import org.alpha3.launcher.resources.click_this_icon_to_enter_and_exit_editmode
import org.alpha3.launcher.resources.cpu_usage
import org.alpha3.launcher.resources.editmode_tip
import org.alpha3.launcher.resources.editmode_tip_drag_tip
import org.alpha3.launcher.resources.enter_url
import org.alpha3.launcher.resources.grid_3x3_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.grid_4x4_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.keyboard_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.light_mode_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.map_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.mouse_24dp_e8eaed_fill0_wght400_grad0_opsz24
import org.alpha3.launcher.resources.now_you_try
import org.alpha3.launcher.resources.set_offset_x
import org.alpha3.launcher.resources.set_offset_y
import org.alpha3.launcher.resources.size_transform
import org.alpha3.launcher.resources.ui_toturial_finish_tip
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.utils.PlatformContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import coil3.compose.LocalPlatformContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.alpha3.launcher.ui.controls.DynamicButtonManager
import org.alpha3.launcher.ui.controls.UIKeyboard
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.controls.UIStateManager.configureControls
import org.alpha3.launcher.ui.controls.UIStateManager.cpuUsageFlow
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.ui.controls.UIStateManager.editMode
import org.alpha3.launcher.ui.controls.UIStateManager.gridAlpha
import org.alpha3.launcher.ui.controls.UIStateManager.gridSize
import org.alpha3.launcher.ui.controls.UIStateManager.gridVisible
import org.alpha3.launcher.ui.controls.UIStateManager.highlightStep
import org.alpha3.launcher.ui.controls.UIStateManager.isCursorVisible
import org.alpha3.launcher.ui.controls.UIStateManager.launchedActivity
import org.alpha3.launcher.ui.controls.UIStateManager.logMessagesFlow
import org.alpha3.launcher.ui.controls.UIStateManager.memoryInfoFlow
import org.alpha3.launcher.ui.controls.UIStateManager.menuAlpha
import org.alpha3.launcher.ui.controls.UIStateManager.menuColor
import org.alpha3.launcher.ui.controls.UIStateManager.showWebView
import org.alpha3.launcher.ui.page.setting.ToggleFeatureSwitch
import org.alpha3.launcher.ui.theme.White
import org.alpha3.launcher.ui.view.LogRepository
import org.alpha3.launcher.ui.view.LogsBox
import org.alpha3.launcher.ui.view.PerformanceHud
import org.alpha3.launcher.ui.view.getBatteryStatus
import org.alpha3.launcher.ui.view.hasInternetPermission
import org.alpha3.launcher.ui.view.startLoggingUpdates
import org.alpha3.launcher.ui.view.startResourceInfoUpdates
import org.alpha3.launcher.utils.GameFilesPreferences
import kotlinx.coroutines.flow.map
import org.alpha3.launcher.utils.TravelMenuPopup
import org.alpha3.launcher.engine.Engine.sendKeyEvent
import org.alpha3.launcher.utils.stringRes
import org.alpha3.launcher.utils.updateConsoleOutput
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

data class MemoryInfo(
    val totalMemory: String,
    val availableMemory: String,
    val usedMemory: String,
    val totalBytes: Long = 0,
    val availableBytes: Long = 0
)

@DelicateCoroutinesApi
@Composable
fun OverlayUI(
    context: PlatformContext,
    virtualKeyboard: Boolean,
    onKeyEvent: (Int) -> Unit,
    blurRadius: Dp = 6.dp,
    shadowColor: Color = Color.White.copy(alpha = 0.6f),
    scaleFactor:Float = 1.2f,
) {
    var expanded by remember { mutableStateOf(false) }
    var expanded2 by remember { mutableStateOf(false) }
    val iconGlowChecked by GameFilesPreferences.getPreference(GameFilesPreferences.ICON_GLOW_KEY, true).collectAsState(initial = true)
    val matchIconColorCheckedFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.MATCH_ICON_COLOR_KEY, "false").map { it.toBoolean() } }
    val matchIconColorChecked by matchIconColorCheckedFlow.collectAsState(initial = false)
    val newFeatureEnabledChecked by GameFilesPreferences.getPreference(GameFilesPreferences.NEW_FEATURE_ENABLED_KEY, false).collectAsState(initial = false)
    var showMouseMenu by remember { mutableStateOf(false) }
    var consoleOutput by remember { mutableStateOf("") }
    val sensitivityMouseFlow = GameFilesPreferences.getNullablePreference(GameFilesPreferences.SENSITIVITY_MOUSE).collectAsState(initial = 900f)
    var sensitivityMouse by remember { mutableFloatStateOf(sensitivityMouseFlow.value ?: 900f) }
    val tutorial by GameFilesPreferences.getPreference(GameFilesPreferences.TUTORIAL_KEY, true).collectAsState(initial = false)
    val offsetXMouseFlow = GameFilesPreferences.getNullablePreference(GameFilesPreferences.OFFSET_X_MOUSE).collectAsState(initial = 0f)
    var offsetXMouse by remember { mutableFloatStateOf(offsetXMouseFlow.value ?: 0f) }
    val offsetYMouseFlow = GameFilesPreferences.getNullablePreference(GameFilesPreferences.OFFSET_Y_MOUSE).collectAsState(initial = 0f)
    var offsetYMouse by remember { mutableFloatStateOf(offsetYMouseFlow.value ?: 0f) }
    val menuCorner by GameFilesPreferences.getPreference(GameFilesPreferences.MENU_CORNER_KEY, 1).collectAsState(initial = 1)

    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded2) 360f else -360f, animationSpec = tween(1000),
        label = ""
    )

    val infiniteTransition = rememberInfiniteTransition()

    // Animate alpha for flashing effect
    val alpha = infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Define the update functions and assign them to the lambdas
    updateConsoleOutput = { newOutput ->
        consoleOutput += newOutput
    }
    sendKeyEvent = onKeyEvent

    LaunchedEffect(offsetYMouseFlow.value) {
        offsetYMouse = offsetYMouseFlow.value ?: 0f
    }
    LaunchedEffect(offsetXMouseFlow.value) {
        offsetXMouse = offsetXMouseFlow.value ?: 0f
    }
    LaunchedEffect(sensitivityMouseFlow.value) {
        sensitivityMouse = sensitivityMouseFlow.value ?: 900f
    }

    LaunchedEffect(UIStateManager.isPerformanceHudEnabled) {
        startResourceInfoUpdates(context)
    }

    // Collect in your UI layer
    LaunchedEffect(UIStateManager.isMemoryInfoEnabled) {
        if (UIStateManager.isMemoryInfoEnabled) {
            startResourceInfoUpdates(context)
        }
        launch {
            memoryInfoFlow.collect { memoryInfoText ->
                UIStateManager.memoryInfoText = memoryInfoText
            }
        }
        launch {
            cpuUsageFlow.collect { cpuUsage ->
                UIStateManager.cpuUsageText = "${stringRes(Res.string.cpu_usage)}: $cpuUsage%"
            }
        }
    }

    // UQM game info: refreshed once a second while its box is on
    LaunchedEffect(UIStateManager.isUqmInfoEnabled) {
        while (UIStateManager.isUqmInfoEnabled) {
            UIStateManager.uqmInfoText = UIStateManager.uqmInfoProvider?.invoke().orEmpty()
            delay(1000.milliseconds)
        }
        UIStateManager.uqmInfoText = ""
    }

    // Battery Status LaunchedEffect
    LaunchedEffect(UIStateManager.isBatteryStatusEnabled) {
        while (UIStateManager.isBatteryStatusEnabled) {
            UIStateManager.batteryStatus = getBatteryStatus(context)
            delay(2000.milliseconds)
        }
        UIStateManager.batteryStatus = ""
    }

    // Collect in your UI layer
    LaunchedEffect(UIStateManager.isLoggingEnabled) {
        if (UIStateManager.isLoggingEnabled) {
            startLoggingUpdates()
        }
        launch {
            logMessagesFlow.collect { logMessages ->
                UIStateManager.logMessagesText = logMessages
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (configureControls && tutorial) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0f, 0f, 0f, 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                if (highlightStep == 1) {
                    Text(
                        text = stringResource(Res.string.add_delete_and_modify_all_buttons_tip),
                        style = TextStyle(
                            color = White,
                            fontSize = 20.sp
                        )
                    )
                }
                if (highlightStep == 2) {
                    Text(
                        text = stringResource(Res.string.click_this_icon_to_enter_and_exit_editmode),
                        style = TextStyle(
                            color = White,
                            fontSize = 20.sp
                        )
                    )
                }
                if (highlightStep == 3) {
                    Text(
                        text = stringResource(Res.string.editmode_tip),
                        style = TextStyle(
                            color = White,
                            fontSize = 20.sp
                        )
                    )
                }
                if (highlightStep == 4) {
                    Text(
                        text = stringResource(Res.string.editmode_tip_drag_tip),
                        style = TextStyle(
                            color = White,
                            fontSize = 20.sp
                        )
                    )
                }
                if (highlightStep == 5) {
                    Text(
                        text = stringResource(Res.string.now_you_try),
                        style = TextStyle(
                            color = White,
                            fontSize = 20.sp
                        )
                    )
                }
                if (highlightStep == 6) {
                    Text(
                        text = stringResource(Res.string.click_the_spinning_yellow_dots_tip),
                        style = TextStyle(
                            color = White,
                            fontSize = 20.sp
                        )
                    )
                }
                if (highlightStep == 7) {
                    var countdown by remember { mutableIntStateOf(5) }
                    editMode = false

                    LaunchedEffect(Unit) {
                        while (countdown > 0) {
                            delay(1000L.milliseconds)
                            countdown -= 1
                        }
                        GameFilesPreferences.setPreference(GameFilesPreferences.WHATS_NEW_KEY, false)
                        GameFilesPreferences.setPreference(GameFilesPreferences.TUTORIAL_KEY, false)
                        // After countdown reaches 0, execute these actions
                        highlightStep = 0
                        launchedActivity = false
                        Platform.features.finishHost()
                    }

                    if (countdown > 0) {
                        Text(
                            text = stringResource(Res.string.ui_toturial_finish_tip, countdown),
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 20.sp
                            ),
                        )
                    }
                }
            }
        }

        val menuAlignment = when (menuCorner) {
            0 -> Alignment.TopEnd
            1 -> Alignment.TopStart
            2 -> Alignment.BottomEnd
            3 -> Alignment.BottomStart
            else -> Alignment.TopEnd
        }

        Surface(
            color = Color.Transparent,
            modifier = Modifier.align(menuAlignment)
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    fadeIn(animationSpec = tween(150, 150)) togetherWith
                            fadeOut(animationSpec = tween(150)) using
                            SizeTransform { initialSize, targetSize ->
                                if (targetState) {
                                    keyframes {
                                        // Expand horizontally first.
                                        IntSize(targetSize.width, initialSize.height) at 150
                                        durationMillis = 600
                                    }
                                } else {
                                    keyframes {
                                        // Shrink vertically first.
                                        IntSize(initialSize.width, targetSize.height) at 150
                                        durationMillis = 600
                                    }
                                }
                            }
                },
                label = stringResource(Res.string.size_transform)
            ) { targetExpanded ->
                if (targetExpanded) {
                    Column(
                        modifier = Modifier
                            .align(menuAlignment)
                            .background(Color(alpha = 0.6f, red = 0f, green = 0f, blue = 0f))
                            .padding(5.dp)
                    ) {
                        PopUpWindow(
                            onClose = { expanded = false }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .align(menuAlignment)
                        .padding(
                            top = if (menuCorner < 2) 15.dp else 0.dp,
                            bottom = if (menuCorner >= 2) 15.dp else 0.dp,
                            start = if (menuCorner % 2 == 1) 15.dp else 0.dp,
                            end = if (menuCorner % 2 == 0) 15.dp else 0.dp
                        )
                ) {
                    val isRightSide = menuCorner % 2 == 0

                    val settingsIcon = @Composable {
                        Box(
                            modifier = Modifier
                                .padding(top = 10.dp, start = 10.dp)
                        ) {
                            if (iconGlowChecked) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .scale(scaleFactor)
                                        .blur(blurRadius),
                                    tint = shadowColor,
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier
                                    .size(30.dp)
                                    .rotate(rotationAngle)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { expanded = !expanded }
                                        )
                                    },
                                tint = if (matchIconColorChecked) {
                                    menuColor.copy(alpha = menuAlpha)
                                } else {
                                    Color.Black
                                }
                            )
                        }
                    }

                    val expansionButton = @Composable {
                        IconButton(
                            onClick = {
                                expanded2 = !expanded2
                                showMouseMenu = false
                                if (configureControls && tutorial && highlightStep == 1) {
                                    highlightStep++
                                }
                                if (!expanded2) {
                                    editMode = false
                                }
                            }
                        ) {
                            val isLeftEdge = menuCorner % 2 == 1
                            val iconTint = if (configureControls && tutorial && highlightStep == 1) {
                                Color.Yellow
                            } else if (matchIconColorChecked) {
                                menuColor.copy(alpha = menuAlpha)
                            } else {
                                Color.Black
                            }

                            val expandedIcon = if (isLeftEdge) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight
                            val collapsedIcon = if (isLeftEdge) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft

                            if (iconGlowChecked) {
                                @Suppress("DEPRECATION")
                                Icon(
                                    imageVector = if (expanded2) expandedIcon else collapsedIcon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .scale(scaleFactor)
                                        .blur(blurRadius)
                                        .alpha(if (configureControls && tutorial && highlightStep == 1) alpha.value else 1f),
                                    tint = shadowColor,
                                )
                            }

                            @Suppress("DEPRECATION")
                            Icon(
                                imageVector = if (expanded2) expandedIcon else collapsedIcon,
                                contentDescription = if (expanded2) "Collapse" else "Expand",
                                modifier = Modifier
                                    .size(30.dp)
                                    .alpha(if (configureControls && tutorial && highlightStep == 1) alpha.value else 1f),
                                tint = iconTint
                            )
                        }
                    }

                    if (isRightSide) {
                        expansionButton()
                    } else {
                        settingsIcon()
                    }

                    AnimatedVisibility(
                        visible = expanded2,
                        enter = expandHorizontally(
                            animationSpec = tween(300),
                            expandFrom = if (menuCorner % 2 == 1) Alignment.Start else Alignment.End
                        ),
                        exit = shrinkHorizontally(
                            animationSpec = tween(300),
                            shrinkTowards = if (menuCorner % 2 == 1) Alignment.Start else Alignment.End
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.Top
                        ) {
                            DynamicButtonManager(
                                context = context,
                                onNewButtonAdded = { newButtonState ->
                                    UIStateManager.addButtonState(newButtonState)
                                }
                            )
                            if (!editMode && !configureControls) {
                                if (newFeatureEnabledChecked) {
                                    if (hasInternetPermission(context)) {
                                        IconButton(
                                            onClick = { showWebView = true }
                                        ) {
                                            val painter = painterResource(Res.drawable.map_24dp_e8eaed_fill0_wght400_grad0_opsz24)
                                            if (iconGlowChecked) {
                                                Image(
                                                    painter = painter,
                                                    contentDescription = "World Map",
                                                    modifier = Modifier
                                                        .size(30.dp)
                                                        .scale(scaleFactor)
                                                        .blur(blurRadius),
                                                    colorFilter = ColorFilter.tint(shadowColor)
                                                )
                                            }
                                            Image(
                                                painter = painter,
                                                contentDescription = "World Map",
                                                modifier = Modifier.size(24.dp),
                                                colorFilter = if (matchIconColorChecked) {
                                                    ColorFilter.tint(menuColor.copy(alpha = menuAlpha))
                                                } else {
                                                    ColorFilter.tint(Color.Black)
                                                }
                                            )
                                        }
                                        if (showWebView) {
                                            WebViewComponent(
                                                onClose = { showWebView = false }
                                            )
                                        }
                                    }
                                }
                                if (isCursorVisible == 1) {
                                    IconButton(
                                        onClick = {
                                            showMouseMenu = !showMouseMenu
                                        }
                                    ) {
                                        val painter = painterResource(Res.drawable.mouse_24dp_e8eaed_fill0_wght400_grad0_opsz24)
                                        if (iconGlowChecked) {
                                            Image(
                                                painter = painter,
                                                contentDescription = "Enable Cursor",
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .scale(scaleFactor)
                                                    .blur(blurRadius),
                                                colorFilter = ColorFilter.tint(shadowColor)
                                            )
                                        }
                                        Image(
                                            painter = painter,
                                            contentDescription = "Enable Cursor",
                                            modifier = Modifier.size(24.dp),
                                            colorFilter = if (matchIconColorChecked) {
                                                ColorFilter.tint(menuColor.copy(alpha = menuAlpha))
                                            } else {
                                                ColorFilter.tint(Color.Black)
                                            }
                                        )
                                    }
                                    if (showMouseMenu) {
                                        val isBottom = menuCorner >= 2
                                        Column(
                                            verticalArrangement = Arrangement.Top,
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .padding(
                                                    top = if (!isBottom) 32.dp else 0.dp,
                                                    bottom = if (isBottom) 32.dp else 0.dp
                                                )
                                                .then(
                                                    if (isBottom) Modifier.offset(y = (-300).dp) else Modifier
                                                )
                                                .background(
                                                    color = Color.White,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(color = customColor)
                                        ) {
                                            Text(
                                                text = "${sensitivityMouse.roundToInt()}",
                                                fontSize = 24.sp,
                                                color = Color.White
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Slider(
                                                    value = sensitivityMouse,
                                                    onValueChange = { newValue ->
                                                        sensitivityMouse = newValue
                                                        CoroutineScope(Dispatchers.Main).launch {
                                                            GameFilesPreferences.setNullablePreference(GameFilesPreferences.SENSITIVITY_MOUSE, newValue)
                                                        }
                                                    },
                                                    valueRange = 400f..9000f,
                                                    steps = 49,
                                                    modifier = Modifier.width(150.dp),
                                                    colors = SliderDefaults.colors(
                                                        thumbColor = Color.Black, // Color of the thumb
                                                        activeTrackColor = Color.Black, // Color of the active track
                                                        inactiveTrackColor = Color.Black, // Color of the inactive track
                                                        activeTickColor = Color.Red, // Color of the active ticks
                                                        inactiveTickColor = Color.Red // Color of the inactive ticks
                                                    )
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(Res.string.set_offset_x),
                                                fontSize = 24.sp,
                                                color = Color.White
                                            )

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        offsetXMouse = (offsetXMouse - 1f).coerceIn(-100f..100f)
                                                        CoroutineScope(Dispatchers.Main).launch {
                                                            GameFilesPreferences.setNullablePreference(GameFilesPreferences.OFFSET_X_MOUSE, offsetXMouse)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.ChevronLeft,
                                                        contentDescription = "Decrease Offset X",
                                                        modifier = Modifier.size(24.dp),
                                                        tint = Color.White
                                                    )
                                                }
                                                Text(
                                                    text = "${offsetXMouse.roundToInt()}",
                                                    fontSize = 24.sp,
                                                    color = Color.White
                                                )
                                                IconButton(
                                                    onClick = {
                                                        offsetXMouse = (offsetXMouse + 1f).coerceIn(-100f..100f)
                                                        CoroutineScope(Dispatchers.Main).launch {
                                                            GameFilesPreferences.setNullablePreference(GameFilesPreferences.OFFSET_X_MOUSE, offsetXMouse)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.ChevronRight,
                                                        contentDescription = "Increase Offset X",
                                                        modifier = Modifier.size(24.dp),
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = stringResource(Res.string.set_offset_y),
                                                fontSize = 24.sp,
                                                color = Color.White
                                            )

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(top = 16.dp)
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        offsetYMouse = (offsetYMouse - 1f).coerceIn(-100f..100f)
                                                        CoroutineScope(Dispatchers.Main).launch {
                                                            GameFilesPreferences.setNullablePreference(GameFilesPreferences.OFFSET_Y_MOUSE, offsetYMouse)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.ArrowDropDown,
                                                        contentDescription = "Decrease Offset Y",
                                                        modifier = Modifier.size(24.dp),
                                                        tint = Color.White
                                                    )
                                                }
                                                Text(
                                                    text = "${offsetYMouse.roundToInt()}",
                                                    fontSize = 24.sp,
                                                    color = Color.White
                                                )
                                                IconButton(
                                                    onClick = {
                                                        offsetYMouse = (offsetYMouse + 1f).coerceIn(-100f..100f)
                                                        CoroutineScope(Dispatchers.Main).launch {
                                                            GameFilesPreferences.setNullablePreference(GameFilesPreferences.OFFSET_Y_MOUSE, offsetYMouse)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.ArrowDropUp,
                                                        contentDescription = "Increase Offset Y",
                                                        modifier = Modifier.size(24.dp),
                                                        tint = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                    IconButton(
                                        onClick = {
                                            if (virtualKeyboard) {
                                                UIKeyboard.showVKB = !UIKeyboard.showVKB
                                            } else {
                                                Platform.features.showSoftKeyboard()
                                            }
                                        }
                                    ) {
                                        val painter = painterResource(Res.drawable.keyboard_24dp_e8eaed_fill0_wght400_grad0_opsz24)
                                        if (iconGlowChecked) {
                                            Image(
                                                painter = painter,
                                                contentDescription = "Show Keyboard",
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .scale(scaleFactor)
                                                    .blur(blurRadius),
                                                colorFilter = ColorFilter.tint(shadowColor)
                                            )
                                        }
                                        Image(
                                            painter = painter,
                                            contentDescription = "Show Keyboard",
                                            modifier = Modifier.size(24.dp),
                                            colorFilter = if (matchIconColorChecked) {
                                                ColorFilter.tint(menuColor.copy(alpha = menuAlpha))
                                            } else {
                                                ColorFilter.tint(Color.Black)
                                            }
                                        )
                                    }

                                TravelMenuPopup(onKeyEvent = onKeyEvent)
                            }

                            if (editMode) {
                                IconButton(onClick = {
                                    gridVisible.value = !gridVisible.value
                                }) {
                                    val painter = painterResource(Res.drawable.grid_3x3_24dp_e8eaed_fill0_wght400_grad0_opsz24)
                                    if (iconGlowChecked) {
                                        Image(
                                            painter = painter,
                                            contentDescription = "Toggle Grid Visibility",
                                            modifier = Modifier
                                                .size(30.dp)
                                                .scale(scaleFactor)
                                                .blur(blurRadius),
                                            colorFilter = ColorFilter.tint(shadowColor)
                                        )
                                    }
                                    Image(
                                        painter = painter,
                                        contentDescription = "Toggle Grid Visibility",
                                        modifier = Modifier.size(24.dp),
                                        colorFilter = if (matchIconColorChecked) {
                                            ColorFilter.tint(menuColor.copy(alpha = menuAlpha))
                                        } else {
                                            ColorFilter.tint(Color.Black)
                                        }
                                    )
                                }
                                IconButton(onClick = {
                                    gridSize.intValue = (gridSize.intValue % 100) + 10
                                }) {
                                    val painter = painterResource(Res.drawable.grid_4x4_24dp_e8eaed_fill0_wght400_grad0_opsz24)
                                    if (iconGlowChecked) {
                                        Image(
                                            painter = painter,
                                            contentDescription = "Change Grid Size",
                                            modifier = Modifier
                                                .size(30.dp)
                                                .scale(scaleFactor)
                                                .blur(blurRadius),
                                            colorFilter = ColorFilter.tint(shadowColor)
                                        )
                                    }
                                    Image(
                                        painter = painter,
                                        contentDescription = "Change Grid Size",
                                        modifier = Modifier.size(24.dp),
                                        colorFilter = if (matchIconColorChecked) {
                                            ColorFilter.tint(menuColor.copy(alpha = menuAlpha))
                                        } else {
                                            ColorFilter.tint(Color.Black)
                                        }
                                    )
                                }
                                IconButton(onClick = {
                                    gridAlpha.floatValue =
                                        if (gridAlpha.floatValue == 0.25f) 0.5f else 0.25f
                                }) {
                                    val painter = painterResource(Res.drawable.light_mode_24dp_e8eaed_fill0_wght400_grad0_opsz24)
                                    if (iconGlowChecked) {
                                        Image(
                                            painter = painter,
                                            contentDescription = "Change Grid Alpha",
                                            modifier = Modifier
                                                .size(30.dp)
                                                .scale(scaleFactor)
                                                .blur(blurRadius),
                                            colorFilter = ColorFilter.tint(shadowColor)
                                        )
                                    }
                                    Image(
                                        painter = painter,
                                        contentDescription = "Change Grid Alpha",
                                        modifier = Modifier.size(24.dp),
                                        colorFilter = if (matchIconColorChecked) {
                                            ColorFilter.tint(menuColor.copy(alpha = menuAlpha))
                                        } else {
                                            ColorFilter.tint(Color.Black)
                                        }
                                    )
                                }
                                IconButton(onClick = {
                                    CoroutineScope(Dispatchers.Main).launch {
                                        GameFilesPreferences.setPreference(GameFilesPreferences.MENU_CORNER_KEY, (menuCorner + 1) % 4)
                                    }
                                }) {
                                    if (iconGlowChecked) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInFull,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(30.dp)
                                                .scale(scaleFactor)
                                                .blur(blurRadius),
                                            tint = shadowColor,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.OpenInFull,
                                        contentDescription = "Change Menu Corner",
                                        modifier = Modifier.size(24.dp),
                                        tint = if (matchIconColorChecked) {
                                            menuColor.copy(alpha = menuAlpha)
                                        } else {
                                            Color.Black
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isRightSide) {
                        settingsIcon()
                    } else {
                        expansionButton()
                    }
                }
            }
        }
        // Information display
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (UIStateManager.isMemoryInfoEnabled) {
                DraggableBox { fontSize, textColor, _, _ ->
                    Text(
                        text = UIStateManager.memoryInfoText,
                        color = textColor,
                        fontSize = fontSize.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (UIStateManager.isBatteryStatusEnabled) {
                DraggableBox { fontSize, textColor, _, _ ->
                    Text(
                        text = UIStateManager.batteryStatus,
                        color = textColor,
                        fontSize = fontSize.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (UIStateManager.isUqmInfoEnabled) {
                DraggableBox(initialWidth = 280f, initialHeight = 170f, followBottom = false) { fontSize, textColor, _, _ ->
                    Text(
                        text = UIStateManager.uqmInfoText,
                        color = textColor,
                        fontSize = fontSize.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (UIStateManager.isLoggingEnabled) {
                DraggableBox { fontSize, textColor, _, _ ->
                    Text(
                        text = UIStateManager.logMessagesText,
                        color = textColor,
                        fontSize = fontSize.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (UIStateManager.isPerformanceHudEnabled) {
                DraggableBox { fontSize, _, boxWidth, boxHeight ->
                    PerformanceHud(boxWidth, boxHeight, fontSize)
                }
            }
            if (UIStateManager.isAppLoggingEnabled) {
                DraggableBox { fontSize, _, boxWidth, boxHeight ->
                    LogsBox(logs = LogRepository.logs, fontSize = fontSize, boxWidth = boxWidth, boxHeight = boxHeight)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = UIStateManager.logMessagesText,
                        fontSize = fontSize.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun DraggableBox(
    initialWidth: Float = 200f,
    initialHeight: Float = 100f,
    /** Logs follow their newest line; a box that reads from the top (like the UQM info) turns this off. */
    followBottom: Boolean = true,
    content: @Composable (Float, Color, Float, Float) -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var boxWidth by remember { mutableFloatStateOf(initialWidth) }
    var boxHeight by remember { mutableFloatStateOf(initialHeight) }
    var isDragging by remember { mutableStateOf(false) }
    var isResizing by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(10f) }
    var useBlackText by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Auto scroll to bottom when content changes
    LaunchedEffect(scrollState.maxValue) {
        if (followBottom) {
            coroutineScope.launch {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(width = boxWidth.dp, height = boxHeight.dp)
            .then(
                if (editMode) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDrag = { _, dragAmount ->
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            },
                            onDragEnd = { isDragging = false }
                        )
                    }
                } else Modifier
            )
            .border(
                2.dp, when {
                    editMode -> Color.Gray
                    isDragging || isResizing -> Color.Red
                    else -> Color.Transparent
                }
            ) // Added condition for editMode being gray
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                contentAlignment = Alignment.Center
            ) {
                val textColor = if (useBlackText) Color.Black else Color.White
                content(fontSize, textColor, boxWidth, boxHeight)
            }
        }

        if (editMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth()
                    .background(Color.Transparent),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = { fontSize = (fontSize - 2f).coerceAtLeast(5f) },
                    modifier = Modifier.alpha(if (editMode) 1f else 0f) // Adjust visibility when not in edit mode
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "Decrease Font Size",
                        tint = Color.Red
                    )
                }
                IconButton(
                    onClick = { fontSize = (fontSize + 2f).coerceAtMost(30f) },
                    modifier = Modifier.alpha(if (editMode) 1f else 0f) // Adjust visibility when not in edit mode
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Increase Font Size",
                        tint = Color.Red
                    )
                }
                IconButton(
                    onClick = { useBlackText = !useBlackText },
                    modifier = Modifier.alpha(if (editMode) 1f else 0f) // Adjust visibility when not in edit mode
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Toggle Text Color",
                        tint = if (useBlackText) Color.Black else Color.White
                    )
                }
                IconButton(
                    onClick = { /* Resize logic */ },
                    modifier = Modifier
                        .alpha(if (editMode) 1f else 0f) // Adjust visibility when not in edit mode
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { isResizing = true },
                                onDrag = { _, dragAmount ->
                                    boxWidth += dragAmount.x
                                    boxHeight += dragAmount.y
                                },
                                onDragEnd = { isResizing = false }
                            )
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Resize Handle",
                        tint = Color.Red,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = -1f // Flip horizontally
                            }
                    )
                }
            }
        }
    }
}

// This is the settings window while in-game when you hit the gear icon. (top left)
@Composable
fun PopUpWindow(
    onClose: () -> Unit
) {
   Dialog(onDismissRequest = onClose) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .fillMaxWidth(0.95f)
            .background(
                Color(alpha = 0.8f, red = 0f, green = 0f, blue = 0f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp)
    ) {
        ToggleFeatureSwitch()
        }
    }
}

// This is the snap to grid for the UI buttons.
@Composable
fun GridOverlay(gridSize: Int, snapX: Float?, snapY: Float?, alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val lineColor = Color.LightGray.copy(alpha = alpha)
        val dotColor = Color.Red.copy(alpha = alpha)
        val highlightColor = Color.Red.copy(alpha = 1.0f)

        // Draw vertical lines
        for (x in 0 until width.toInt() step gridSize) {
            drawLine(
                color = lineColor,
                start = Offset(x.toFloat(), 0f),
                end = Offset(x.toFloat(), height),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw horizontal lines
        for (y in 0 until height.toInt() step gridSize) {
            drawLine(
                color = lineColor,
                start = Offset(0f, y.toFloat()),
                end = Offset(width, y.toFloat()),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw dots at intersections
        for (x in 0 until width.toInt() step gridSize) {
            for (y in 0 until height.toInt() step gridSize) {
                val color = if (snapX == x.toFloat() && snapY == y.toFloat()) highlightColor else dotColor
                drawCircle(
                    color = color,
                    radius = 2.dp.toPx(),
                    center = Offset(x.toFloat(), y.toFloat())
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewComponent(onClose: () -> Unit) {
    val context = LocalPlatformContext.current
    var url by remember { mutableStateOf("https://en.uesp.net/wiki/Morrowind:Morrowind") }
    var webController by remember { mutableStateOf<WebController?>(null) }

    // Position state for dragging
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Size state
    var width by remember { mutableStateOf(600.dp) }

    run {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart
        ) {
            // Draggable surface
            Surface(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .zIndex(Float.MAX_VALUE),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.LightGray)
                            .height(48.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        offsetX += dragAmount.x
                                        offsetY += dragAmount.y
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = "Drag Handle",
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(36.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            offsetX += dragAmount.x
                                            offsetY += dragAmount.y
                                        }
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.weight(0.5f))
                        // URL field
                        TextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier
                                .weight(2f)
                                .padding(horizontal = 8.dp),
                            label = { Text(stringResource(Res.string.enter_url)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    // Load the new URL in the WebView
                                    webController?.loadUrl(url)
                                }
                            )
                        )
                        Spacer(modifier = Modifier.weight(0.5f))
                        // Keyboard button
                        IconButton(
                            onClick = { Platform.features.showSoftKeyboard() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = "Show Keyboard"
                            )
                        }
                        // Close button
                        IconButton(
                            onClick = {
                                onClose()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close WebView"
                            )
                        }
                    }

                    // WebView content
                    PlatformWebView(
                        url = url,
                        modifier = Modifier.fillMaxSize(),
                        onController = { webController = it }
                    )
                }
            }
        }
    }
}
