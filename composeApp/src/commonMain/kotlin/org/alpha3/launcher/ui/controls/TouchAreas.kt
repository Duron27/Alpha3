package org.alpha3.launcher.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.engine.KeyCodes
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.utils.SystemInfo

// Touch areas that stand in for a thumbstick the user turned off. Each has its own pointer input, so both work at once
// and with the buttons: touches the engine gets straight from the surface lose every finger after the first. Only in
// gameplay; with the engine's cursor shown they are not there and taps reach the engine's menus. Draw them beneath the
// buttons so the buttons keep their touches.

/** Whether the left thumbstick is on (the default); off, [TouchMoveArea] takes the left half of the screen. */
@Composable
fun rememberLeftThumbEnabled(): Boolean {
    val enabled by GameFilesPreferences.getPreference(GameFilesPreferences.ENABLE_LEFT_THUMBSTICK, true)
        .collectAsState(initial = true)
    return enabled
}

/** Shown in place of a turned-off thumbstick: in gameplay, not while the controls are being laid out or edited. */
fun touchAreaActive(): Boolean =
    UIStateManager.tempCodeGroup == "OpenMW" && UIStateManager.isCursorVisible == 0 &&
        !UIStateManager.configureControls && !UIStateManager.editMode

/**
 * With the dynamic right thumbstick off, the right half of the screen turns the camera: a finger dragged there moves
 * the mouse relatively, as the thumbstick does, with the same sensitivity.
 */
@Composable
fun TouchLookArea(containerWidth: Float) {
    val sensitivity by GameFilesPreferences.getNullablePreference(GameFilesPreferences.SENSITIVITY_RT)
        .collectAsState(initial = 2500f)
    val currentSensitivity by rememberUpdatedState(sensitivity ?: 2500f)

    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f).fillMaxHeight())
        Box(
            Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                val (reportedWidth, _) = SystemInfo.services.deviceRealSize()
                val screenWidth = (if (reportedWidth > 0) reportedWidth else containerWidth.roundToInt()).coerceAtLeast(1)
                awaitEachGesture {
                    // follow the finger that started the gesture; another finger landing here later is ignored
                    val id = awaitFirstDown(requireUnconsumed = false).id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta.x != 0f || delta.y != 0f) {
                            Engine.onNativeMouse(
                                0, 2,
                                delta.x * currentSensitivity / screenWidth,
                                delta.y * currentSensitivity / screenWidth,
                                true
                            )
                        }
                        change.consume()
                    }
                }
            }
        )
    }
}

/**
 * With the left thumbstick off, the left half of the screen walks: where a finger lands is the stick's centre, and
 * dragging away from it moves as the thumbstick would (W A S D, Shift past 90% with auto run, or the virtual gamepad's
 * left stick). The stick's reach is the left thumbstick's size. A faint ring shows it while a finger is down.
 */
@Composable
fun TouchMoveArea() {
    val autoRun by GameFilesPreferences.getPreference(GameFilesPreferences.AUTO_RUN, true).collectAsState(initial = true)
    val virtualPref by GameFilesPreferences.getPreference(GameFilesPreferences.VIRTUAL_LEFT_THUMBSTICK, true)
        .collectAsState(initial = true)
    val currentAutoRun by rememberUpdatedState(autoRun)
    val currentVirtual by rememberUpdatedState(virtualPref && Platform.features.supportsVirtualGamepad)
    val buttonStates by UIStateManager.buttonStates.collectAsState()
    val stickSize = buttonStates[99]?.size?.takeIf { it > 0f } ?: 160f
    val radiusPx = with(LocalDensity.current) { (stickSize / 2).dp.toPx() }
    val currentRadius by rememberUpdatedState(radiusPx)

    var centre by remember { mutableStateOf<Offset?>(null) }
    var knob by remember { mutableStateOf(Offset.Zero) }

    Row(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                val keys = StickKeys()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    val origin = down.position
                    val virtual = currentVirtual
                    centre = origin
                    knob = Offset.Zero
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == id } ?: break
                            if (!change.pressed) break
                            val r = currentRadius
                            val x = (change.position.x - origin.x).coerceIn(-r, r)
                            val y = (change.position.y - origin.y).coerceIn(-r, r)
                            knob = Offset(x, y)
                            if (virtual) updateStickTest(0, x / r, y / r)
                            else keys.update(x / r, y / r, currentAutoRun)
                            change.consume()
                        }
                    } finally {
                        // also when the area leaves mid-gesture (a menu opened): nothing may stay held
                        if (virtual) updateStickTest(0, 0f, 0f)
                        keys.releaseAll()
                        centre = null
                    }
                }
            }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = centre ?: return@Canvas
                drawCircle(Color.White.copy(alpha = 0.25f), radius = radiusPx, center = c, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.25f), radius = radiusPx * 0.35f, center = c + knob)
            }
        }
        Spacer(Modifier.weight(1f).fillMaxHeight())
    }
}

/** W A S D (and Shift) from a stick's position, -1..1 on each axis, pressing and releasing only on a change. */
private class StickKeys {
    private var w = false
    private var a = false
    private var s = false
    private var d = false
    private var shift = false

    fun update(x: Float, y: Float, autoRun: Boolean) {
        set(KeyCodes.KEYCODE_SHIFT_LEFT, autoRun && sqrt(x * x + y * y) > 0.9f, shift) { shift = it }
        set(KeyCodes.KEYCODE_W, y < -DEAD_ZONE, w) { w = it }
        set(KeyCodes.KEYCODE_S, y > DEAD_ZONE, s) { s = it }
        set(KeyCodes.KEYCODE_A, x < -DEAD_ZONE, a) { a = it }
        set(KeyCodes.KEYCODE_D, x > DEAD_ZONE, d) { d = it }
    }

    fun releaseAll() = update(0f, 0f, false)

    private inline fun set(key: Int, wanted: Boolean, held: Boolean, store: (Boolean) -> Unit) {
        if (wanted == held) return
        if (wanted) Engine.onNativeKeyDown(key) else Engine.onNativeKeyUp(key)
        store(wanted)
    }

    private companion object {
        // the left thumbstick's own dead zone, a fifth of its reach
        const val DEAD_ZONE = 0.2f
    }
}
