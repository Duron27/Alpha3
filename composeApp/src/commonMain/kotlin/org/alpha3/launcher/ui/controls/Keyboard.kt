package org.alpha3.launcher.ui.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.engine.KeyCodes
import org.alpha3.launcher.ui.view.vibrateHelper
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.TranslateText
import kotlin.math.roundToInt
import kotlin.random.Random

object UIKeyboard {
    var showVKB by mutableStateOf(false)
    /** Shift is down (held or latched for the next key): keys show and type their shifted character. */
    var isShiftPressed by mutableStateOf(false)
    /** Caps lock: letters only, as on a real keyboard. */
    var isCapsLock by mutableStateOf(false)
}

private val BACKLIGHTS = listOf(1f, 0.75f, 0.5f, 0.25f)
private val THEMES = listOf("darkMode", "lightMode", "RGB", "gold")

private enum class Kind {
    /** Types its character (and presses its key, so game bindings see it). */
    CHAR,
    /** A key with no character (Esc, Enter, arrows...): down while held. */
    KEY,
    /** Shift, Ctrl, Alt: down while held; a tap alone latches it for the next key (sticky keys). */
    MODIFIER,
    CAPS, FN, THEME, LIGHT,
    /** Drag to move the keyboard. */
    MOVE,
    /** Drag to resize the keyboard. */
    SCALE,
    /** An empty space in the row. */
    GAP,
}

/**
 * One key: [label] and [shifted] are what it shows and types, [units] its width in key widths (every row is 15),
 * [code] the key it presses, [repeat] whether holding it repeats (backspace, arrows).
 */
private class Key(
    val label: String,
    val shifted: String = label,
    val units: Float = 1f,
    val kind: Kind = Kind.CHAR,
    val code: Int? = null,
    val repeat: Boolean = false,
    /** Text it also types (space). */
    val text: String? = null,
)

private fun chars(normal: String, shifted: String) = normal.indices.map { Key(normal[it].toString(), shifted[it].toString()) }
private fun key(label: String, code: Int, units: Float = 1f, repeat: Boolean = false, text: String? = null) =
    Key(label, label, units, Kind.KEY, code, repeat, text)
private fun gap(units: Float) = Key("", units = units, kind = Kind.GAP)

private val FN_ROW = listOf(key("Esc", KeyCodes.KEYCODE_ESCAPE)) +
    listOf(
        KeyCodes.KEYCODE_F1, KeyCodes.KEYCODE_F2, KeyCodes.KEYCODE_F3, KeyCodes.KEYCODE_F4,
        KeyCodes.KEYCODE_F5, KeyCodes.KEYCODE_F6, KeyCodes.KEYCODE_F7, KeyCodes.KEYCODE_F8,
        KeyCodes.KEYCODE_F9, KeyCodes.KEYCODE_F10, KeyCodes.KEYCODE_F11, KeyCodes.KEYCODE_F12,
    ).mapIndexed { i, c -> key("F${i + 1}", c, 0.875f) } +
    listOf(
        key("Del", KeyCodes.KEYCODE_FORWARD_DEL, 1.25f, repeat = true),
        key("Home", KeyCodes.KEYCODE_MOVE_HOME, 1.125f),
        key("End", KeyCodes.KEYCODE_MOVE_END, 1.125f),
    )

private val ROWS = listOf(
    chars("`1234567890-=", "~!@#$%^&*()_+") + key("⌫", KeyCodes.KEYCODE_DEL, 2f, repeat = true),
    listOf(key("Tab", KeyCodes.KEYCODE_TAB, 1.5f)) + chars("qwertyuiop[]", "QWERTYUIOP{}") + Key("\\", "|", 1.5f),
    listOf(Key("Caps", units = 1.75f, kind = Kind.CAPS)) + chars("asdfghjkl;'", "ASDFGHJKL:\"") +
        key("⏎", KeyCodes.KEYCODE_ENTER, 2.25f),
    listOf(Key("Shift", units = 2.25f, kind = Kind.MODIFIER, code = KeyCodes.KEYCODE_SHIFT_LEFT)) +
        chars("zxcvbnm,./", "ZXCVBNM<>?") +
        Key("Shift", units = 1.75f, kind = Kind.MODIFIER, code = KeyCodes.KEYCODE_SHIFT_LEFT) +
        key("↑", KeyCodes.KEYCODE_DPAD_UP, repeat = true),
    listOf(
        Key("⚙", kind = Kind.THEME),
        Key("☀", kind = Kind.LIGHT),
        Key("Fn", kind = Kind.FN),
        Key("Ctrl", units = 1.25f, kind = Kind.MODIFIER, code = KeyCodes.KEYCODE_CTRL_LEFT),
        Key("Alt", units = 1.25f, kind = Kind.MODIFIER, code = KeyCodes.KEYCODE_ALT_LEFT),
        key("␣", KeyCodes.KEYCODE_SPACE, 4.5f, text = " "),
        Key("✥", kind = Kind.MOVE),
        Key("⤡", kind = Kind.SCALE),
        key("←", KeyCodes.KEYCODE_DPAD_LEFT, repeat = true),
        key("↓", KeyCodes.KEYCODE_DPAD_DOWN, repeat = true),
        key("→", KeyCodes.KEYCODE_DPAD_RIGHT, repeat = true),
    ),
)

private const val ROW_UNITS = 15f
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 50L

/** Sticky modifiers: which are down, and whether each was latched by a tap (released after the next key). */
private class Modifiers {
    val held = mutableStateMapOf<Int, Boolean>()      // code -> latched
    /** Something other than a modifier was pressed while a modifier was held. */
    var usedWhileHeld = false

    fun isDown(code: Int) = code in held

    /** After a non-modifier key: latched modifiers come back up. */
    fun releaseLatched() {
        for ((code, latched) in held.toMap()) if (latched) {
            Engine.onNativeKeyUp(code)
            held.remove(code)
        }
        syncShift()
    }

    fun releaseAll() {
        for (code in held.keys.toList()) Engine.onNativeKeyUp(code)
        held.clear()
        syncShift()
    }

    fun syncShift() {
        UIKeyboard.isShiftPressed = KeyCodes.KEYCODE_SHIFT_LEFT in held
    }
}

@Composable
fun VirtualKeyboard() {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val themeMode by GameFilesPreferences.getPreference(GameFilesPreferences.KEYBOARD_THEME, "lightMode").collectAsState(initial = "lightMode")
    val backLight by GameFilesPreferences.getPreference(GameFilesPreferences.KEYBOARD_BACKLIGHT, 1f).collectAsState(initial = 1f)
    val translate by GameFilesPreferences.getPreference(GameFilesPreferences.TRANSLATION_ENABLED_KEY, false).collectAsState(initial = false)
    val vibrationFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
    val vibrate by vibrationFlow.collectAsState(initial = true)

    // the touch handlers live as long as the keyboard: they read these, never a value from when they started
    val themeNow = rememberUpdatedState(themeMode)
    val lightNow = rememberUpdatedState(backLight)
    val vibrateNow = rememberUpdatedState(vibrate)

    var fnRow by remember { mutableStateOf(false) }
    val modifiers = remember { Modifiers() }
    DisposableEffect(Unit) { onDispose { modifiers.releaseAll() } }

    // the whole screen, so the keyboard can be moved anywhere on it (nothing here takes touches but the keys)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = maxWidth.value
        val maxH = maxHeight.value
        val minW = 280f
        val minH = 120f
        // sizes in dp, kept on screen
        var width by remember { mutableFloatStateOf(700f) }
        var height by remember { mutableFloatStateOf(200f) }
        var x by remember { mutableFloatStateOf(0f) }
        var y by remember { mutableFloatStateOf(0f) }
        fun clampAll() {
            width = width.coerceIn(minW, maxOf(minW, maxW))
            height = height.coerceIn(minH, maxOf(minH, maxH * 0.9f))
            x = x.coerceIn(0f, maxOf(0f, maxW - width))
            y = y.coerceIn(0f, maxOf(0f, maxH - height))
        }
        // the saved size and place, read once: while dragging, and while a save is being written, the keyboard's own
        // values are the truth (reading the store back mixed a new width with an old height)
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            fun stored(k: androidx.datastore.preferences.core.Preferences.Key<Float>) = GameFilesPreferences.getNullablePreference(k)
            stored(GameFilesPreferences.KEYBOARD_WIDTH).first()?.let { width = it }
            stored(GameFilesPreferences.KEYBOARD_HEIGHT).first()?.let { height = it }
            stored(GameFilesPreferences.KEYBOARD_X).first()?.let { x = it }
            stored(GameFilesPreferences.KEYBOARD_Y).first()?.let { y = it }
            clampAll()
            loaded = true
        }
        LaunchedEffect(maxW, maxH) { if (loaded) clampAll() }
        fun save() = scope.launch {
            GameFilesPreferences.setPreference(GameFilesPreferences.KEYBOARD_WIDTH, width)
            GameFilesPreferences.setPreference(GameFilesPreferences.KEYBOARD_HEIGHT, height)
            GameFilesPreferences.setPreference(GameFilesPreferences.KEYBOARD_X, x)
            GameFilesPreferences.setPreference(GameFilesPreferences.KEYBOARD_Y, y)
        }

        // the rainbow only runs in the RGB theme, and is read only while drawing
        val rgbPhase: State<Float> = if (themeMode == "RGB") {
            rememberInfiniteTransition(label = "RGB").animateFloat(
                0f, 3f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "phase"
            )
        } else remember { mutableFloatStateOf(0f) }
        val rainbow = listOf(Color.Red, Color.Green, Color.Blue)
        fun themeColor(keyPhase: Float): Color {
            val base = when (themeNow.value) {
                "darkMode" -> Color.Black
                "lightMode" -> Color.White
                "gold" -> UIStateManager.gold
                "RGB" -> {
                    val t = rgbPhase.value + keyPhase
                    val i = t.toInt() % 3
                    lerp(rainbow[i], rainbow[(i + 1) % 3], t % 1f)
                }
                else -> Color.LightGray
            }
            return base.copy(alpha = lightNow.value)
        }

        val rows = if (fnRow) listOf(FN_ROW) + ROWS else ROWS
        val rowHeight: Dp = (height / rows.size).dp
        val fontBase = with(density) { (rowHeight * 0.36f).toSp() }
        val fontWord = with(density) { (rowHeight * 0.26f).toSp() }

        Column(
            Modifier
                .offset { IntOffset((x * density.density).roundToInt(), (y * density.density).roundToInt()) }
                .width(width.dp)
        ) {
            for (row in rows) key(row) { Row(Modifier.width(width.dp).height(rowHeight)) {
                for (k in row) {
                    val w = Modifier.weight(k.units / ROW_UNITS).fillMaxHeight()
                    if (k.kind == Kind.GAP) { Box(w); continue }
                    val keyNow = rememberUpdatedState(k)
                    val keyPhase = remember { Random.nextFloat() * 0.5f }
                    var pressed by remember { mutableStateOf(false) }
                    val charCode = remember(k.label) { if (k.kind == Kind.CHAR) Engine.keyCodeForChar(k.label.lowercase()) else null }
                    val charCodeNow = rememberUpdatedState(charCode)
                    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    // the pulse of a press: 0 just pressed, 1 faded out (read only while drawing)
                    val pulse = remember { Animatable(1f) }
                    val lit = pressed || (k.kind == Kind.MODIFIER && modifiers.isDown(k.code!!)) ||
                        (k.kind == Kind.CAPS && UIKeyboard.isCapsLock) || (k.kind == Kind.FN && fnRow)

                    Box(
                        w
                            .padding(2.dp)
                            .drawBehind {
                                val c = themeColor(keyPhase)
                                drawRect(Color.Black.copy(alpha = 0.25f))
                                if (lit) drawRect(c.copy(alpha = c.alpha * 0.4f))
                                drawRect(c, style = Stroke(width = 2.dp.toPx()))
                                val p = pulse.value
                                if (p < 1f) {
                                    val fade = (1f - p) * (1f - p)
                                    val grow = 7.dp.toPx() * p
                                    drawRect(c.copy(alpha = c.alpha * 0.45f * fade))
                                    drawRect(
                                        c.copy(alpha = c.alpha * fade),
                                        topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                                        size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                                        style = Stroke(width = 3.dp.toPx() * (1f - p) + 1f)
                                    )
                                }
                            }
                            .onGloballyPositioned { coords = it }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    // the key as it was when pressed: its release matches its press even if
                                    // shift or caps change the row meanwhile
                                    val key = keyNow.value
                                    val code = charCodeNow.value
                                    pressed = true
                                    scope.launch { pulse.snapTo(0f); pulse.animateTo(1f, tween(320, easing = LinearEasing)) }
                                    if (vibrateNow.value) vibrateHelper(50, 50)
                                    var repeater: Job? = null
                                    when (key.kind) {
                                        Kind.CHAR -> {
                                            val caps = UIKeyboard.isCapsLock && key.label[0].isLetter()
                                            val shift = UIKeyboard.isShiftPressed
                                            val text = if (shift != caps) key.shifted else key.label
                                            code?.let { Engine.onNativeKeyDown(it) }
                                            Engine.nativeCommitText(text, 0)
                                            if (modifiers.held.isNotEmpty()) modifiers.usedWhileHeld = true
                                        }
                                        Kind.KEY -> {
                                            Engine.onNativeKeyDown(key.code!!)
                                            key.text?.let { Engine.nativeCommitText(it, 0) }
                                            if (modifiers.held.isNotEmpty()) modifiers.usedWhileHeld = true
                                            if (key.repeat) repeater = scope.launch {
                                                delay(REPEAT_DELAY_MS)
                                                while (isActive) {
                                                    Engine.onNativeKeyDown(key.code)
                                                    delay(REPEAT_EVERY_MS)
                                                }
                                            }
                                        }
                                        Kind.MODIFIER -> {
                                            val c = key.code!!
                                            if (modifiers.held[c] == true) {
                                                // tapping a latched modifier lets it go
                                                Engine.onNativeKeyUp(c)
                                                modifiers.held.remove(c)
                                            } else if (c !in modifiers.held) {
                                                Engine.onNativeKeyDown(c)
                                                modifiers.held[c] = false
                                                modifiers.usedWhileHeld = false
                                            }
                                            modifiers.syncShift()
                                        }
                                        Kind.CAPS -> UIKeyboard.isCapsLock = !UIKeyboard.isCapsLock
                                        Kind.FN -> fnRow = !fnRow
                                        Kind.THEME -> scope.launch {
                                            val next = THEMES[(THEMES.indexOf(themeNow.value) + 1) % THEMES.size]
                                            GameFilesPreferences.setPreference(GameFilesPreferences.KEYBOARD_THEME, next)
                                        }
                                        Kind.LIGHT -> scope.launch {
                                            val next = BACKLIGHTS[(BACKLIGHTS.indexOf(lightNow.value) + 1) % BACKLIGHTS.size]
                                            GameFilesPreferences.setPreference(GameFilesPreferences.KEYBOARD_BACKLIGHT, next)
                                        }
                                        Kind.MOVE, Kind.SCALE, Kind.GAP -> {}
                                    }

                                    // follow the finger until it lifts. Moving and resizing go by where the finger is on
                                    // the screen against where it went down, so the handle moving under it changes nothing.
                                    fun onScreen(p: androidx.compose.ui.geometry.Offset) = coords?.localToRoot(p) ?: p
                                    val start = onScreen(down.position)
                                    val x0 = x; val y0 = y; val w0 = width; val h0 = height
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (key.kind == Kind.MOVE || key.kind == Kind.SCALE) {
                                            val now = onScreen(change.position)
                                            val dx = (now.x - start.x) / density.density
                                            val dy = (now.y - start.y) / density.density
                                            if (key.kind == Kind.MOVE) { x = x0 + dx; y = y0 + dy } else { width = w0 + dx; height = h0 + dy }
                                            clampAll()
                                        }
                                        change.consume()
                                        if (!change.pressed) break
                                    }

                                    repeater?.cancel()
                                    pressed = false
                                    when (key.kind) {
                                        Kind.CHAR -> {
                                            code?.let { Engine.onNativeKeyUp(it) }
                                            modifiers.releaseLatched()
                                        }
                                        Kind.KEY -> {
                                            Engine.onNativeKeyUp(key.code!!)
                                            modifiers.releaseLatched()
                                        }
                                        Kind.MODIFIER -> {
                                            val c = key.code!!
                                            if (modifiers.held[c] == false) {
                                                if (modifiers.usedWhileHeld) {
                                                    // held while typing: up with the finger
                                                    Engine.onNativeKeyUp(c)
                                                    modifiers.held.remove(c)
                                                } else {
                                                    modifiers.held[c] = true  // a tap: latched for the next key
                                                }
                                            }
                                            modifiers.syncShift()
                                        }
                                        Kind.MOVE, Kind.SCALE -> save()
                                        else -> {}
                                    }
                                }
                            }
                    ) {
                        val shown = when {
                            k.kind != Kind.CHAR -> k.label
                            (UIKeyboard.isShiftPressed != (UIKeyboard.isCapsLock && k.label[0].isLetter())) -> k.shifted
                            else -> k.label
                        }
                        // only word labels are translated; a character is what it types
                        var label by remember(shown) { mutableStateOf(shown) }
                        if (translate && shown.length > 1 && shown.all { it.isLetter() }) {
                            TranslateText(inputText = shown, onTranslationResult = { label = it })
                        }
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = if (label.length > 2) fontWord else fontBase,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.Center)
                                // tinted while drawing, so the rainbow does not rebuild the keys every frame
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(themeColor(keyPhase), blendMode = BlendMode.SrcIn)
                                }
                        )
                    }
                }
            } }
        }
    }
}
