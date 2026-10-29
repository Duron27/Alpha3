package org.alpha3.launcher.ui.controls

import org.alpha3.launcher.ui.view.rememberIconPainter
import kotlinx.coroutines.IO
import org.alpha3.launcher.resources.apply_globally
import org.alpha3.launcher.resources.auto_run
import org.alpha3.launcher.resources.close
import org.alpha3.launcher.resources.colors
import org.alpha3.launcher.resources.increase_size_decrease_size
import org.alpha3.launcher.resources.interact
import org.alpha3.launcher.resources.options
import org.alpha3.launcher.resources.show_in_mouse_ui
import org.alpha3.launcher.resources.virtual_left_thumbstick
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.engine.KeyCodes
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.utils.PlatformContext
import org.alpha3.launcher.utils.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.composables.core.ScrollArea
import com.composables.core.Thumb
import com.composables.core.VerticalScrollbar
import com.composables.core.rememberScrollAreaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import org.alpha3.launcher.engine.MouseActions
import org.alpha3.launcher.utils.Platform
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.alpha3.launcher.engine.Engine.onNativeKeyDown
import org.alpha3.launcher.engine.Engine.onNativeKeyUp
import org.alpha3.launcher.ui.controls.UIStateManager.configureControls
import org.alpha3.launcher.ui.controls.UIStateManager.editMode
import org.alpha3.launcher.ui.controls.UIStateManager.globalColorChange
import org.alpha3.launcher.ui.controls.UIStateManager.isCursorVisible
import org.alpha3.launcher.ui.controls.UIStateManager.menuAlpha
import org.alpha3.launcher.ui.controls.UIStateManager.menuColor
import org.alpha3.launcher.ui.controls.UIStateManager.showWebView
import org.alpha3.launcher.ui.controls.UIStateManager.updateButtonState
import org.alpha3.launcher.ui.overlay.getAnimations
import org.alpha3.launcher.utils.ColorPickerWheel
import org.alpha3.launcher.utils.CustomIconPickerButton
import org.alpha3.launcher.utils.GameFilesPreferences
import kotlinx.coroutines.flow.map
import org.alpha3.launcher.utils.fromHex
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ResizableDraggableThumbstick(
    context: PlatformContext,
    containerWidth: Float,
    containerHeight: Float,
) {
    val buttonStates by UIStateManager.buttonStates.collectAsState()
    val buttonState = buttonStates[99]
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = ""
    )
    val rotationThumb by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = ""
    )
    buttonState?.let { state ->
        var buttonSize by remember { mutableStateOf(state.size.dp) }
        var hexCode by remember { mutableStateOf(state.color) }
        var buttonColor by remember { mutableStateOf(Color.fromHex(hexCode)) }
        var buttonAlpha by remember { mutableFloatStateOf(state.alpha) }
        val buttonTint = remember { mutableStateOf(state.buttonTint) }
        val offsetX = remember { mutableFloatStateOf(state.offsetX) }
        val offsetY = remember { mutableFloatStateOf(state.offsetY) }
        val scrollState = rememberScrollState()
        val stateSB = rememberScrollAreaState(scrollState)
        val buttonUri = remember { mutableStateOf(state.uri) }
        val selectedAnimation by GameFilesPreferences.getPreference(GameFilesPreferences.SELECTED_ANIMATION_KEY, "None").collectAsState(initial = "None")
        val autoMouseMode by GameFilesPreferences.getPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid").collectAsState(initial = "Hybrid")
        val autoRUN by GameFilesPreferences.getPreference(GameFilesPreferences.AUTO_RUN, true).collectAsState(initial = true)
        var offset by remember { mutableStateOf(IntOffset.Zero) }
        val density = LocalDensity.current
        val radiusPx = with(density) { (buttonSize / 2).toPx() }
        val deadZone = 0.2f * radiusPx
        var touchState by remember { mutableStateOf(Offset(0f, 0f)) }
        val isDragging = remember { mutableStateOf(false) }

        // Track key states to prevent redundant events
        var isWPressed by remember { mutableStateOf(false) }
        var isAPressed by remember { mutableStateOf(false) }
        var isSPressed by remember { mutableStateOf(false) }
        var isDPressed by remember { mutableStateOf(false) }
        var isShiftPressed by remember { mutableStateOf(false) }

        var showControlsPopup by remember { mutableStateOf(false) }
        val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
        val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
        val isVirtualLeftPref by GameFilesPreferences.getPreference(GameFilesPreferences.VIRTUAL_LEFT_THUMBSTICK, true).collectAsState(initial = true)
        // Read by the long-running pointer handler, so it has to be State (a plain val would be captured once).
        val isVirtualLeft by remember { derivedStateOf { isVirtualLeftPref && Platform.features.supportsVirtualGamepad } }
        val buttonGroupSwitch by GameFilesPreferences.getPreference(GameFilesPreferences.BUTTON_GROUP_SWITCH_KEY, false).collectAsState(initial = false)
        val controllerConnected = Engine.isControllerConnected()
        val painter: Painter? = state.uri?.let { rememberIconPainter(it) }

        // While the engine shows its cursor (menus) the stick steers the cursor instead of walking.
        val steeringCursor = !isVirtualLeft && isCursorVisible == 1 && touchState != Offset.Zero
        LaunchedEffect(steeringCursor) {
            while (steeringCursor) {
                val dx = touchState.x / radiusPx
                val dy = touchState.y / radiusPx
                val moveX = if (abs(dx) < 0.2f) 0f else dx * CURSOR_SPEED
                val moveY = if (abs(dy) < 0.2f) 0f else dy * CURSOR_SPEED
                if (moveX != 0f || moveY != 0f) Engine.onNativeMouse(0, MouseActions.ACTION_MOVE, moveX, moveY, true)
                delay(16)
            }
        }

        // Update hexCode, buttonColor, and buttonAlpha to use the global color and alpha if globalColorChange is enabled
        LaunchedEffect(globalColorChange) {
            if (globalColorChange) {
                hexCode = UIStateManager.globalColor
                buttonColor = Color.fromHex(hexCode)
                buttonAlpha = UIStateManager.globalAlpha
            } else {
                hexCode = state.color
                buttonColor = Color.fromHex(hexCode)
                buttonAlpha = state.alpha
            }
        }

        // Manage what's in the tabs here
        var options by remember { mutableStateOf(true) }
        var interact by remember { mutableStateOf(false) }
        var colors by remember { mutableStateOf(false) }

        fun resetStates() {
            options = false
            interact = false
            colors = false
        }

        val thumbColor =
            if (isDragging.value) Color.Red.copy(alpha = buttonAlpha) else buttonColor.copy(
                alpha = buttonAlpha
            )

        menuColor = buttonColor
        menuAlpha = buttonAlpha

        val saveState = {
            val updatedState = state.copy(
                size = buttonSize.value,
                offsetX = offsetX.floatValue,
                offsetY = offsetY.floatValue,
                color = hexCode,
                alpha = buttonAlpha,
                uri = buttonUri.value,
                group = 1,
                vibrate = false,
                buttonTint = buttonTint.value
            )
            updateButtonState(99, updatedState)
            UIStateManager.saveButtonState(containerWidth, containerHeight)
        }

        val animations = getAnimations().toMutableMap().apply { put("None", EnterTransition.None to ExitTransition.None) }
        val (currentEnterAnimation, currentExitAnimation) = animations[selectedAnimation] ?: (animations["Slide Vertically"] ?: error("Default animation not found"))

        val selectedKeycodesFlow = remember(context) {
            GameFilesPreferences.getPreference(GameFilesPreferences.SELECTED_MOUSE_KEYS, "54,111").map { keycodeString ->
                keycodeString.split(",").map { it.toInt() }
            }
        }
        val selectedKeycodes by selectedKeycodesFlow.collectAsState(initial = emptyList())
        val isOnMouseScreen by remember { derivedStateOf { 99 in selectedKeycodes } }
        val visibility = when {
            isCursorVisible == 0 && autoMouseMode == "None" -> !isUIHidden
            isCursorVisible == 0 && autoMouseMode == "Hybrid" || 99 in selectedKeycodes -> !isUIHidden
            isCursorVisible == 0 && autoMouseMode == "Hybrid" || 99 !in selectedKeycodes -> isUIHidden
            isCursorVisible == 0 -> !isUIHidden
            else -> !isUIHidden
        }

        val visibilityFinal = if (controllerConnected || UIKeyboard.showVKB || showWebView) {
            isUIHidden
        } else {
            visibility
        }

        AnimatedVisibility(
            visible = visibilityFinal,
            enter = currentEnterAnimation,
            exit = currentExitAnimation
        ) {
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .graphicsLayer {
                        translationX = offsetX.floatValue
                        translationY = offsetY.floatValue
                    }
                    .background(Color.Transparent)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(buttonSize)
                        .background(Color.Transparent)
                        .then(
                            if (editMode) {
                                Modifier.pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            isDragging.value = true
                                        },
                                        onDrag = { _, dragAmount ->
                                            offsetX.floatValue += dragAmount.x
                                            offsetY.floatValue += dragAmount.y
                                        },
                                        onDragEnd = {
                                            isDragging.value = false
                                            saveState()
                                        }
                                    )
                                }
                            } else Modifier
                        )
                        .border(2.dp, if (painter == null) thumbColor else Color.Transparent, CircleShape)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                Modifier.pointerInput(Unit) {
                                    if (!configureControls) {
                                        awaitPointerEventScope {
                                            // The stick follows the finger that touched it. event.type describes the whole gesture
                                            // (Android derives it from the MotionEvent): another finger pressing or lifting elsewhere,
                                            // on the jump button for instance, arrives here as a Press or Release and used to zero the
                                            // stick until the next move. Only this finger's own state counts.
                                            var activePointer: PointerId? = null
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val tracked = activePointer?.let { id -> event.changes.firstOrNull { it.id == id } }
                                                    ?: event.changes.firstOrNull { it.pressed }
                                                val steering = tracked?.pressed == true
                                                val releasing = !steering && activePointer != null
                                                // Mouse hover, or a finger that never touched the stick
                                                if (!steering && !releasing) continue
                                                activePointer = if (steering) tracked!!.id else null
                                                val location = tracked?.position ?: Offset.Zero
                                                when {
                                                    steering -> {
                                                        val newX = (location.x - radiusPx).coerceIn(
                                                            -radiusPx,
                                                            radiusPx
                                                        )
                                                        val newY = (location.y - radiusPx).coerceIn(
                                                            -radiusPx,
                                                            radiusPx
                                                        )
                                                        touchState = Offset(newX, newY)

                                                        val xRatio = touchState.x / radiusPx
                                                        val yRatio = touchState.y / radiusPx
                                                        val distanceFromCenter =
                                                            sqrt(xRatio * xRatio + yRatio * yRatio)

                                                        if (isVirtualLeft) {
                                                            val clampedX = xRatio.coerceIn(-1f, 1f)
                                                            val clampedY = yRatio.coerceIn(-1f, 1f)
                                                            updateStickTest(0, clampedX, clampedY)
                                                        } else {
                                                            if (autoRUN) {
                                                                // Handle shift key (90%+ tilt in any direction)
                                                                if (distanceFromCenter > 0.9f) {
                                                                    if (!isShiftPressed) {
                                                                        onNativeKeyDown(KeyCodes.KEYCODE_SHIFT_LEFT)
                                                                        isShiftPressed = true
                                                                    }
                                                                } else if (isShiftPressed) {
                                                                    onNativeKeyUp(KeyCodes.KEYCODE_SHIFT_LEFT)
                                                                    isShiftPressed = false
                                                                }
                                                            }

                                                            // Handle movement keys with state tracking
                                                            val shouldPressW = isCursorVisible == 0 && touchState.y < -deadZone
                                                            val shouldPressS = isCursorVisible == 0 && touchState.y > deadZone
                                                            val shouldPressA = isCursorVisible == 0 && touchState.x < -deadZone
                                                            val shouldPressD = isCursorVisible == 0 && touchState.x > deadZone

                                                            if (shouldPressW != isWPressed) {
                                                                if (shouldPressW) onNativeKeyDown(KeyCodes.KEYCODE_W) else onNativeKeyUp(KeyCodes.KEYCODE_W)
                                                                isWPressed = shouldPressW
                                                            }
                                                            if (shouldPressS != isSPressed) {
                                                                if (shouldPressS) onNativeKeyDown(KeyCodes.KEYCODE_S) else onNativeKeyUp(KeyCodes.KEYCODE_S)
                                                                isSPressed = shouldPressS
                                                            }
                                                            if (shouldPressA != isAPressed) {
                                                                if (shouldPressA) onNativeKeyDown(KeyCodes.KEYCODE_A) else onNativeKeyUp(KeyCodes.KEYCODE_A)
                                                                isAPressed = shouldPressA
                                                            }
                                                            if (shouldPressD != isDPressed) {
                                                                if (shouldPressD) onNativeKeyDown(KeyCodes.KEYCODE_D) else onNativeKeyUp(KeyCodes.KEYCODE_D)
                                                                isDPressed = shouldPressD
                                                            }
                                                        }
                                                    }

                                                    releasing -> {
                                                        if (isVirtualLeft) {
                                                            touchState = Offset.Zero
                                                            updateStickTest(0, 0f, 0f)
                                                        } else {
                                                            touchState = Offset.Zero
                                                            if (isWPressed) { onNativeKeyUp(KeyCodes.KEYCODE_W); isWPressed = false }
                                                            if (isAPressed) { onNativeKeyUp(KeyCodes.KEYCODE_A); isAPressed = false }
                                                            if (isSPressed) { onNativeKeyUp(KeyCodes.KEYCODE_S); isSPressed = false }
                                                            if (isDPressed) { onNativeKeyUp(KeyCodes.KEYCODE_D); isDPressed = false }
                                                            if (isShiftPressed) {
                                                                onNativeKeyUp(KeyCodes.KEYCODE_SHIFT_LEFT)
                                                                isShiftPressed = false
                                                            }
                                                        }
                                                    }

                                                    else -> Unit
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                    ) {
                        if (painter != null) {
                            Image(
                                painter = painter,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(buttonAlpha)
                                    .graphicsLayer(
                                        rotationZ = if (editMode) rotationThumb else 0f
                                    ),
                                colorFilter = if (buttonTint.value) {
                                    ColorFilter.tint(thumbColor)
                                } else {
                                    null
                                }
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(25.dp)
                                .graphicsLayer {
                                    translationX = touchState.x
                                    translationY = touchState.y
                                }
                                .background(
                                    thumbColor,
                                    shape = CircleShape
                                )
                                .border(if (painter == null) 2.dp else 0.dp, thumbColor, CircleShape)
                        )
                    }
                    // Small button at the top right outside the border, relative to buttonSize
                    if (buttonGroupSwitch) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(
                                    x = -(buttonSize / 20).value.dp,
                                    y = (buttonSize / 20).value.dp
                                )
                                .size(20.dp)
                                .background(Color.Transparent, shape = CircleShape)
                                .clickable {
                                    UIStateManager.buttonsGroup =
                                        if (UIStateManager.buttonsGroup == 1) 2 else 1
                                }
                                .border(1.dp, thumbColor, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${UIStateManager.buttonsGroup}",
                                color = White,
                                fontSize = 8.sp,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                    if (editMode) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .size(30.dp)
                                .background(Color.Black, shape = CircleShape)
                                .clickable { showControlsPopup = true }
                                .border(2.dp, Color.DarkGray, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = Color.Red,
                                modifier = Modifier.graphicsLayer(
                                    rotationZ = rotation
                                )
                            )
                        }
                        if (showControlsPopup) {
                            Popup(
                                alignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Transparent),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(300.dp)
                                            .offset { offset }
                                            .padding(top = 5.dp)
                                            .background(
                                                Color.Black.copy(alpha = 0.8f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                2.dp,
                                                Color(202, 165, 96),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .pointerInput(Unit) {
                                                detectDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    offset = IntOffset(
                                                        offset.x + dragAmount.x.roundToInt(),
                                                        offset.y // + dragAmount.y.roundToInt()
                                                    )
                                                }
                                            }
                                    ) {
                                        Column {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val selectedTabIndex = when {
                                                    options -> 0
                                                    interact -> 1
                                                    colors -> 2
                                                    else -> 0
                                                }
                                                ScrollableTabRow(
                                                    selectedTabIndex = selectedTabIndex,
                                                    edgePadding = 1.dp,
                                                    contentColor = White,
                                                    containerColor = Color.Transparent,
                                                    modifier = Modifier.weight(1f),
                                                    indicator = { tabPositions ->
                                                        TabRowDefaults.Indicator(
                                                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                                            color = Color(202, 165, 96)
                                                        )
                                                    }
                                                ) {
                                                    Tab(
                                                        selected = options,
                                                        onClick = {
                                                            resetStates()
                                                            options = true
                                                        },
                                                        text = { Text(stringResource(Res.string.options)) }
                                                    )
                                                    Tab(
                                                        selected = interact,
                                                        onClick = {
                                                            resetStates()
                                                            interact = true
                                                        },
                                                        text = { Text(stringResource(Res.string.interact)) }
                                                    )
                                                    Tab(
                                                        selected = colors,
                                                        onClick = {
                                                            resetStates()
                                                            colors = true
                                                        },
                                                        text = { Text(stringResource(Res.string.colors)) }
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        showControlsPopup = false
                                                        saveState()
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = stringResource(Res.string.close),
                                                        tint = White
                                                    )
                                                }
                                            }

                                            ScrollArea(state = stateSB) {
                                                Column(
                                                    modifier = Modifier
                                                        .padding(16.dp)
                                                        .verticalScroll(scrollState)
                                                ) {
                                                    if (options) {
                                                        CustomIconPickerButton(
                                                            context = context,
                                                            buttonId = 99,
                                                            buttonUri = buttonUri,
                                                            containerWidth = containerWidth,
                                                            containerHeight = containerHeight
                                                        )

                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(8.dp)
                                                        ) {
                                                            Text(
                                                                text = stringResource(Res.string.increase_size_decrease_size),
                                                                color = White,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(bottom = 8.dp)
                                                            )
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.SpaceEvenly, // Even spacing
                                                                modifier = Modifier
                                                                    .height(48.dp)
                                                                    .fillMaxWidth()
                                                            ) {
                                                                // Increase size button (+)
                                                                IconButton(
                                                                    onClick = {
                                                                        buttonSize += 10.dp
                                                                        saveState()
                                                                    },
                                                                    modifier = Modifier
                                                                        .size(40.dp)
                                                                        .background(
                                                                            Color.Black,
                                                                            CircleShape
                                                                        )
                                                                        .border(
                                                                            2.dp,
                                                                            White,
                                                                            CircleShape
                                                                        )
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Add,
                                                                        contentDescription = "Increase size",
                                                                        tint = White,
                                                                        modifier = Modifier.size(24.dp)
                                                                    )
                                                                }

                                                                // Decrease size button (-)
                                                                IconButton(
                                                                    onClick = {
                                                                        buttonSize -= 10.dp
                                                                        if (buttonSize < 50.dp) buttonSize = 50.dp
                                                                        saveState()
                                                                    },
                                                                    modifier = Modifier
                                                                        .size(40.dp)
                                                                        .background(
                                                                            Color.Black,
                                                                            CircleShape
                                                                        )
                                                                        .border(
                                                                            2.dp,
                                                                            White,
                                                                            CircleShape
                                                                        )
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Remove,
                                                                        contentDescription = "Decrease size",
                                                                        tint = White,
                                                                        modifier = Modifier.size(24.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                    if (interact) {
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            modifier = Modifier
                                                                .height(40.dp)
                                                                .fillMaxWidth()
                                                        ) {
                                                            Text(
                                                                text = stringResource(Res.string.show_in_mouse_ui),
                                                                color = White,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Spacer(modifier = Modifier.weight(1f))
                                                            Switch(
                                                                checked = isOnMouseScreen,
                                                                onCheckedChange = { isChecked ->
                                                                    val updatedKeycodes = if (isChecked) {
                                                                        selectedKeycodes + 99
                                                                    } else {
                                                                        selectedKeycodes - 99
                                                                    }.joinToString(",")
                                                                    CoroutineScope(Dispatchers.Main).launch {
                                                                        GameFilesPreferences.setPreference(GameFilesPreferences.SELECTED_MOUSE_KEYS, updatedKeycodes)
                                                                    }
                                                                },
                                                                colors = SwitchDefaults.colors(
                                                                    checkedThumbColor = Color(202, 165, 96),
                                                                    uncheckedThumbColor = Color.Red,
                                                                    checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                                                    uncheckedTrackColor = Color.Black.copy(alpha = 0.5f),
                                                                    checkedBorderColor = White,
                                                                    uncheckedBorderColor = White
                                                                )
                                                            )
                                                        }
                                                        HorizontalDivider(color = Color(202, 165, 96), thickness = 1.dp)
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            modifier = Modifier
                                                                .height(40.dp)
                                                                .fillMaxWidth()
                                                        ) {
                                                            Text(
                                                                text = stringResource(Res.string.auto_run),
                                                                color = White,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Spacer(modifier = Modifier.weight(1f))
                                                            Switch(
                                                                checked = autoRUN,
                                                                onCheckedChange = { isChecked ->
                                                                    CoroutineScope(Dispatchers.Main).launch {
                                                                        GameFilesPreferences.setPreference(GameFilesPreferences.AUTO_RUN, isChecked)
                                                                    }
                                                                },
                                                                colors = SwitchDefaults.colors(
                                                                    checkedThumbColor = Color(202, 165, 96),
                                                                    uncheckedThumbColor = Color.Red,
                                                                    checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                                                    uncheckedTrackColor = Color.Black.copy(alpha = 0.5f),
                                                                    checkedBorderColor = White,
                                                                    uncheckedBorderColor = White
                                                                )
                                                            )
                                                        }
                                                        HorizontalDivider(color = Color(202, 165, 96), thickness = 1.dp)
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 8.dp)
                                                        ) {
                                                            Text(
                                                                text = stringResource(Res.string.virtual_left_thumbstick),
                                                                color = White,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(bottom = 8.dp)
                                                            )
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.Center,
                                                                modifier = Modifier.fillMaxWidth()
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Gamepad,
                                                                    contentDescription = null,
                                                                    tint = if (isVirtualLeft) Color(202, 165, 96) else White.copy(alpha = 0.3f),
                                                                    modifier = Modifier.size(28.dp)
                                                                )
                                                                Spacer(modifier = Modifier.width(16.dp))
                                                                Switch(
                                                                    checked = !isVirtualLeft,
                                                                    onCheckedChange = { isChecked ->
                                                                        CoroutineScope(Dispatchers.IO).launch {
                                                                            GameFilesPreferences.setPreference(GameFilesPreferences.VIRTUAL_LEFT_THUMBSTICK, !isChecked)
                                                                        }
                                                                    },
                                                                    colors = SwitchDefaults.colors(
                                                                        checkedThumbColor = Color(202, 165, 96),
                                                                        uncheckedThumbColor = Color(202, 165, 96),
                                                                        checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                                                        uncheckedTrackColor = Color.Black.copy(alpha = 0.9f),
                                                                        checkedBorderColor = White,
                                                                        uncheckedBorderColor = White
                                                                    )
                                                                )
                                                                Spacer(modifier = Modifier.width(16.dp))
                                                                Icon(
                                                                    imageVector = Icons.Default.Keyboard,
                                                                    contentDescription = null,
                                                                    tint = if (!isVirtualLeft) Color(202, 165, 96) else White.copy(alpha = 0.3f),
                                                                    modifier = Modifier.size(28.dp)
                                                                )
                                                            }
                                                        }
                                                        HorizontalDivider(color = Color(202, 165, 96), thickness = 1.dp)
                                                    }

                                                    if (colors) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            modifier = Modifier
                                                                .height(40.dp)
                                                                .fillMaxWidth()
                                                        ) {
                                                            Text(
                                                                text = "Colorize Icon?",
                                                                color = White,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                            Spacer(modifier = Modifier.weight(1f))
                                                            Switch(
                                                                checked = buttonTint.value,
                                                                onCheckedChange = {
                                                                    buttonTint.value = it
                                                                    saveState()
                                                                },
                                                                colors = SwitchDefaults.colors(
                                                                    checkedThumbColor = Color(202, 165, 96),
                                                                    uncheckedThumbColor = Color.Red,
                                                                    checkedTrackColor = Color.Black.copy(alpha = 0.9f),
                                                                    uncheckedTrackColor = Color.Black.copy(alpha = 0.5f),
                                                                    checkedBorderColor = White,
                                                                    uncheckedBorderColor = White
                                                                )
                                                            )
                                                        }
                                                        HorizontalDivider(color = Color(202, 165, 96), thickness = 1.dp)

                                                        if (buttonTint.value) {
                                                            ColorPickerWheel(
                                                                initialColor = buttonColor,
                                                                onColorSelected = { color, hex, alphaValue ->
                                                                    hexCode = hex
                                                                    buttonAlpha = alphaValue
                                                                    buttonColor = color
                                                                    saveState()
                                                                    Log.d("ColorWheel", "Selected Color: $color, Hex: $hex, Alpha: $alphaValue")
                                                                }
                                                            )
                                                        } else {
                                                            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                                                                Text("Colorize icon disabled", color = White)
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Row {
                                                            Button(
                                                                onClick = {
                                                                    UIStateManager.changeAllButtonColorsAndAlpha(hexCode, buttonAlpha)
                                                                    globalColorChange = !globalColorChange
                                                                },
                                                                modifier = Modifier.width(IntrinsicSize.Max),
                                                                colors = ButtonDefaults.buttonColors(
                                                                    containerColor = Color(202, 165, 96),
                                                                    contentColor = White,
                                                                    disabledContainerColor = Color.Gray,
                                                                    disabledContentColor = White.copy(alpha = 0.5f)
                                                                ),
                                                                elevation = ButtonDefaults.buttonElevation(
                                                                    defaultElevation = 4.dp,
                                                                    pressedElevation = 8.dp,
                                                                    disabledElevation = 0.dp
                                                                )
                                                            ) {
                                                                Text(
                                                                    text = stringResource(Res.string.apply_globally),
                                                                    fontSize = 12.sp
                                                                )
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
                                                        modifier = Modifier.background(
                                                            Color(202, 165, 96).copy(0.3f), RoundedCornerShape(100)
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Cursor speed at full stick deflection, in engine points per frame. */
private const val CURSOR_SPEED = 14f
