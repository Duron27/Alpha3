package org.alpha3.launcher.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.engine.MouseActions
import org.alpha3.launcher.globals.PlayBridge
import org.alpha3.launcher.ui.controls.MouseIcon
import org.alpha3.launcher.ui.controls.ResizableDraggableButton
import org.alpha3.launcher.ui.controls.ResizableDraggableRightThumbstick
import org.alpha3.launcher.ui.controls.ResizableDraggableThumbstick
import org.alpha3.launcher.ui.controls.ScrollWheelIndicator
import org.alpha3.launcher.ui.controls.UIKeyboard
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.controls.VirtualKeyboard
import org.alpha3.launcher.ui.view.BackgroundAnimation
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.PlatformContext
import kotlin.math.roundToInt

/** Button ids that are drawn by dedicated composables (thumbsticks, scroll wheel, ...) rather than as plain buttons. */
private val SpecialButtonIds = listOf(99, 98, 101, 201)

/**
 * The in-game OpenMW controls (buttons, thumbsticks, overlay menu, virtual keyboard, ...), the same
 * stack Android's EngineActivity shows on top of the SDL surface.
 *
 * The root is transparent. [forwardTouches] sends touches that no control consumed to the engine as
 * mouse input, for hosts where this view sits above the SDL surface instead of next to it.
 */
@OptIn(DelicateCoroutinesApi::class)
@Composable
fun GameplayScreen(
    context: PlatformContext,
    forwardTouches: Boolean = false,
    landscapeOnly: Boolean = false,
    /** Runs before the saved control layout is loaded (e.g. to install the default layout on first run). */
    prepare: suspend () -> Unit = {},
    onKeyEvent: (Int) -> Unit = { keyCode ->
        Engine.onNativeKeyDown(keyCode)
        Engine.onNativeKeyUp(keyCode)
    },
) {
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val containerWidth = size.width.toFloat()
    val containerHeight = size.height.toFloat()
    val ready = containerWidth > 1f && (!landscapeOnly || size.width > size.height)

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        if (!ready) return@Box

        LaunchedEffect(size) {
            // A host that rotates into landscape (iOS) reports several sizes while the rotation animates, and each
            // control remembers the position of its first load: wait until the size settles (a newer size cancels this).
            if (landscapeOnly) delay(300)
            UIStateManager.containerGlobalWidth.floatValue = containerWidth
            UIStateManager.containerGlobalHeight.floatValue = containerHeight
            // Engine coordinates are in points.
            Engine.resolutionX = (containerWidth / density).roundToInt()
            Engine.resolutionY = (containerHeight / density).roundToInt()
            UIStateManager.launchedActivity = true
            prepare()
            UIStateManager.loadButtonState(containerWidth, containerHeight)
        }

        // Mirrors Android's AutoMouseModeComposable: follow the engine's cursor state every frame.
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { }
                val shown = if (Engine.isMouseShown()) 1 else 0
                if (UIStateManager.isCursorVisible != shown) UIStateManager.isCursorVisible = shown
            }
        }

        val isUIHiddenFlow = remember {
            GameFilesPreferences.getPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() }
        }
        val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
        val autoMouseMode by GameFilesPreferences.getPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid")
            .collectAsState(initial = "Hybrid")
        val virtualKeyboard by GameFilesPreferences.getPreference(GameFilesPreferences.USE_VIRTUAL_KB, true)
            .collectAsState(initial = true)

        if (forwardTouches) TouchToMouse(density)

        val snapX = remember { mutableStateOf<Float?>(null) }
        val snapY = remember { mutableStateOf<Float?>(null) }

        AnimatedVisibility(
            visible = UIStateManager.configureControls,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) { BackgroundAnimation() }

        AnimatedVisibility(
            visible = UIStateManager.editMode && UIStateManager.gridVisible.value,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            GridOverlay(
                gridSize = UIStateManager.gridSize.intValue,
                snapX = snapX.value,
                snapY = snapY.value,
                alpha = UIStateManager.gridAlpha.floatValue
            )
        }

        AnimatedVisibility(
            visible = UIStateManager.isCursorVisible == 1 && UIStateManager.tempCodeGroup == "OpenMW" && !isUIHidden,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            if (autoMouseMode != "None" || Engine.isControllerConnected()) {
                MouseIcon(containerWidth = containerWidth, containerHeight = containerHeight)
            }
        }

        if (!isUIHidden) {
            OverlayUI(context = context, virtualKeyboard = virtualKeyboard, onKeyEvent = onKeyEvent)
        }

        GameplayButtons(context, containerWidth, containerHeight)

        AnimatedVisibility(
            visible = UIStateManager.enableRightThumb && UIStateManager.tempCodeGroup == "OpenMW",
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            ResizableDraggableRightThumbstick(context, containerWidth, containerHeight)
        }

        ResizableDraggableThumbstick(context, containerWidth, containerHeight)

        AnimatedVisibility(
            visible = UIKeyboard.showVKB,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Box(Modifier.wrapContentSize()) { VirtualKeyboard() }
        }

        AnimatedVisibility(
            visible = UIStateManager.isCursorVisible == 0 && UIStateManager.enableQuickSlot,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            HiddenMenu(context = context, containerWidth = containerWidth, containerHeight = containerHeight)
        }

        AnimatedVisibility(
            visible = UIStateManager.isRadialMenuExpanded,
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            Box(Modifier.wrapContentSize()) { ExpandableCircleButton() }
        }

        ScrollWheelIndicator(context = context, containerWidth = containerWidth, containerHeight = containerHeight)

    }
}

@Composable
private fun GameplayButtons(context: PlatformContext, containerWidth: Float, containerHeight: Float) {
    val buttonStates by UIStateManager.buttonStates.collectAsState()
    buttonStates.values
        .filter { it.id !in SpecialButtonIds }
        .forEach { button ->
            ResizableDraggableButton(
                context = context,
                id = button.id,
                keyCode = button.keyCode,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                onDelete = { UIStateManager.removeButtonState(button.id, containerWidth, containerHeight) }
            )
        }
}

/**
 * Bottom layer: pointer input nothing above consumed becomes mouse input for the engine (touch to cursor,
 * mouse hover, the mouse buttons) while the engine shows its cursor, plus the scroll wheel. SDL does this itself
 * for input that reaches its own view, which this layer sits on top of.
 */
@Composable
private fun TouchToMouse(density: Float) {
    Box(
        Modifier.fillMaxSize().pointerInput(density) {
            awaitPointerEventScope {
                // Mask of the mouse buttons sent as pressed so far: 1 primary, 2 secondary, 4 tertiary.
                var sent = 0
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val x = change.position.x / density
                    val y = change.position.y / density

                    if (event.type == PointerEventType.Scroll) {
                        // Compose: positive = scrolled down; SDL wheel: positive = up.
                        Engine.onNativeMouse(0, MouseActions.ACTION_SCROLL, 0f, -change.scrollDelta.y, false)
                        continue
                    }

                    // Only a shown engine cursor (menus) takes pointer input. In gameplay touches on empty screen
                    // do nothing, as on Android. A press already sent is still released if the cursor goes away.
                    val cursorShown = UIStateManager.isCursorVisible == 1
                    if (!cursorShown && sent == 0) continue

                    // A finger or pen has no button state; treat it as the primary button.
                    val wanted = if (!change.pressed || !cursorShown) 0 else {
                        val b = event.buttons
                        val mask = (if (b.isPrimaryPressed) 1 else 0) or
                            (if (b.isSecondaryPressed) 2 else 0) or
                            (if (b.isTertiaryPressed) 4 else 0)
                        if (mask == 0) 1 else mask
                    }

                    if (cursorShown) Engine.onNativeMouse(0, MOVE, x, y, false)
                    if (wanted != sent) {
                        for (bit in intArrayOf(1, 2, 4)) {
                            val isDown = wanted and bit != 0
                            val wasDown = sent and bit != 0
                            if (isDown != wasDown) {
                                Engine.onNativeMouse(
                                    bit,
                                    if (isDown) MouseActions.ACTION_DOWN else MouseActions.ACTION_UP,
                                    x, y, false
                                )
                            }
                        }
                        sent = wanted
                    }
                }
            }
        }
    )
}

private const val MOVE = 2
