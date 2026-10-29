@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import org.alpha3.launcher.engine.EngineBridge
import org.alpha3.launcher.engine.MouseActions
import org.alpha3.launcher.globals.PlayBridge
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.memset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * iOS [EngineBridge]: calls SDL's C entry points in the running process (libopenmw exports them).
 * Symbols are resolved lazily and cached, because dlsym walks every loaded image under dyld's locks and
 * the engine calls [isMouseShown] every frame. Until the engine library is loaded every call is a no-op.
 */
class IosEngineBridge : EngineBridge {
    private val self = dlopen(null, RTLD_NOW)

    private val sendKeySym = Symbol<CFunction<(Int, Int) -> Unit>>("SDL_SendVirtualKeyboardKey")
    private val sendMouseButtonSym = Symbol<CFunction<(COpaquePointer?, UInt, UByte, UByte) -> Int>>("SDL_SendMouseButton")
    private val sendMouseMotionSym = Symbol<CFunction<(COpaquePointer?, UInt, Int, Int, Int) -> Int>>("SDL_SendMouseMotion")
    private val sendMouseWheelSym = Symbol<CFunction<(COpaquePointer?, UInt, Float, Float, Int) -> Int>>("SDL_SendMouseWheel")
    private val showCursorSym = Symbol<CFunction<(Int) -> Int>>("SDL_ShowCursor")
    private val getMouseStateSym = Symbol<CFunction<(COpaquePointer?, COpaquePointer?) -> UInt>>("SDL_GetMouseState")
    private val sendKeyboardTextSym = Symbol<CFunction<(CPointer<ByteVar>?) -> Int>>("SDL_SendKeyboardText")
    private val relativeModeSym = Symbol<CFunction<() -> Int>>("SDL_GetRelativeMouseMode")
    private val attachVirtualSym = Symbol<CFunction<(COpaquePointer?) -> Int>>("SDL_JoystickAttachVirtualEx")
    private val joystickOpenSym = Symbol<CFunction<(Int) -> COpaquePointer?>>("SDL_JoystickOpen")
    private val setVirtualAxisSym = Symbol<CFunction<(COpaquePointer?, Int, Short) -> Int>>("SDL_JoystickSetVirtualAxis")

    private val sendKey get() = sendKeySym.get()
    private val sendMouseButton get() = sendMouseButtonSym.get()
    private val sendMouseMotion get() = sendMouseMotionSym.get()
    private val sendMouseWheel get() = sendMouseWheelSym.get()
    private val showCursor get() = showCursorSym.get()
    private val getMouseState get() = getMouseStateSym.get()
    private val sendKeyboardText get() = sendKeyboardTextSym.get()
    private val relativeMode get() = relativeModeSym.get()

    /**
     * A lazily resolved C symbol. SDL is not necessarily loaded when the controls start polling, so a miss is
     * never cached; the retry is throttled because dlsym walks every loaded image under dyld's locks.
     */
    private inner class Symbol<T : CPointed>(private val name: String) {
        private var cached: CPointer<T>? = null
        private var nextTry = TimeSource.Monotonic.markNow()

        fun get(): CPointer<T>? {
            cached?.let { return it }
            if (nextTry.hasPassedNow()) {
                cached = dlsym(self, name)?.reinterpret<T>()
                if (cached == null) nextTry = TimeSource.Monotonic.markNow() + 500.milliseconds
                else log("bridge: resolved $name")
            }
            return cached
        }
    }

    // Android key codes -> SDL scancodes.
    /** Sends an SDL scancode directly (hardware keyboards report USB HID usages, which are SDL scancodes). */
    fun sendScancode(scancode: Int, down: Boolean) {
        if (keyLogBudget > 0) {
            keyLogBudget--
            log("hw key ${if (down) "down" else "up"} sdl=$scancode")
        }
        sendKey?.invoke(if (down) 1 else 0, scancode)
    }

    override fun onNativeKeyDown(keyCode: Int) = key(keyCode, 1)
    override fun onNativeKeyUp(keyCode: Int) = key(keyCode, 0)

    private var keyLogBudget = 80

    private fun key(keyCode: Int, state: Int) {
        val scancode = androidKeyToSdlScancode(keyCode)
        val fn = sendKey
        if (keyLogBudget > 0) {
            keyLogBudget--
            log("key ${if (state == 1) "down" else "up"} android=$keyCode sdl=$scancode fn=${if (fn == null) "MISSING" else "ok"}")
        }
        if (scancode == null) return
        fn?.invoke(state, scancode)
    }

    override fun onNativeMouse(button: Int, action: Int, x: Float, y: Float, relative: Boolean) {
        when (action) {
            MouseActions.ACTION_DOWN, MouseActions.ACTION_UP -> {
                val state = if (action == MouseActions.ACTION_DOWN) 1.toUByte() else 0.toUByte()
                // Android button masks (1 primary, 2 secondary, 4 tertiary) -> SDL buttons (1 left, 3 right, 2 middle).
                val sdlButton = when (button) {
                    2 -> 3
                    4 -> 2
                    else -> 1
                }.toUByte()
                sendMouseButton?.invoke(null, 0u, state, sdlButton)
            }
            MouseActions.ACTION_SCROLL -> sendMouseWheel?.invoke(null, 0u, x, y, 0)
            else -> if (relative) relativeMotion(x, y)
                else sendMouseMotion?.invoke(null, 0u, 0, x.toInt(), y.toInt())
        }
    }

    // SDL takes whole-number deltas; keep the fraction so slow drags are not rounded away.
    private var carryX = 0f
    private var carryY = 0f

    private fun relativeMotion(dx: Float, dy: Float) {
        val fx = dx + carryX
        val fy = dy + carryY
        val ix = fx.toInt()
        val iy = fy.toInt()
        carryX = fx - ix
        carryY = fy - iy
        if (ix != 0 || iy != 0) sendMouseMotion?.invoke(null, 0u, 1, ix, iy)
    }

    override fun getMouseX() = mousePosition().first
    override fun getMouseY() = mousePosition().second

    private fun mousePosition(): Pair<Int, Int> {
        val fn = getMouseState ?: return 0 to 0
        return memScoped {
            val x = alloc<IntVar>()
            val y = alloc<IntVar>()
            fn.invoke(x.ptr, y.ptr)
            x.value to y.value
        }
    }

    private var lastShown: Boolean? = null

    override fun isMouseShown(): Boolean {
        val fn = showCursor ?: return false
        val shown = fn.invoke(-1) == 1
        if (shown != lastShown) {
            lastShown = shown
            log("cursor shown=$shown relativeMode=${relativeMode?.invoke()}")
        }
        return shown
    }

    private fun log(line: String) {
        println("IosEngineBridge: $line")
        PlayBridge.addLog("[input] $line")
    }

    // Text fields read SDL_TEXTINPUT, not key events (the virtual keyboard sends the key press itself).
    override fun nativeCommitText(text: String, newCursorPosition: Int) {
        val fn = sendKeyboardText
        if (keyLogBudget > 0) log("text '$text' fn=${if (fn == null) "MISSING" else "ok"}")
        if (fn == null) return
        memScoped { fn.invoke(text.cstr.getPointer(this)) }
    }

    override fun keyCodeForChar(char: String): Int? {
        val c = char.singleOrNull()?.lowercaseChar() ?: return null
        return when (c) {
            in 'a'..'z' -> 29 + (c - 'a')
            in '0'..'9' -> 7 + (c - '0')
            ' ' -> 62
            ',' -> 55
            '.' -> 56
            '-' -> 69
            '=' -> 70
            '[' -> 71
            ']' -> 72
            '\\' -> 73
            ';' -> 74
            '\'' -> 75
            '/' -> 76
            '`' -> 68
            else -> null
        }
    }

    override fun isControllerConnected() = false

    private var virtualJoystick: COpaquePointer? = null

    /**
     * Registers the on-screen sticks as a virtual gamepad through SDL's virtual joystick API. Android does the
     * same through its JNI hotplug path; here SDL builds the standard gamepad mapping from the descriptor.
     */
    override fun nativeAddJoystick(
        deviceId: Int, name: String, desc: String, vendorId: Int, productId: Int,
        isAccelerometer: Boolean, buttonMask: Int, nAxes: Int, axisMask: Int, nHats: Int, nBalls: Int
    ): Int {
        if (virtualJoystick != null) return 0
        val attach = attachVirtualSym.get() ?: return -1
        val open = joystickOpenSym.get() ?: return -1
        val index = memScoped {
            // SDL_VirtualJoystickDesc (LP64): u16 version,type,naxes,nbuttons,nhats,vendor,product,padding;
            // u32 button_mask, axis_mask; then name, userdata and six callback pointers.
            val d = allocArray<ByteVar>(VIRTUAL_DESC_SIZE)
            memset(d, 0, VIRTUAL_DESC_SIZE.convert())
            val u16 = d.reinterpret<UShortVar>()
            u16[0] = 1u                       // SDL_VIRTUAL_JOYSTICK_DESC_VERSION
            u16[1] = 1u                       // SDL_JOYSTICK_TYPE_GAMECONTROLLER
            u16[2] = nAxes.toUShort()
            u16[3] = STANDARD_BUTTONS.toUShort()
            u16[4] = nHats.toUShort()
            u16[5] = vendorId.toUShort()
            u16[6] = productId.toUShort()
            val u32 = (d + 16)!!.reinterpret<UIntVar>()
            u32[0] = ((1 shl STANDARD_BUTTONS) - 1).toUInt() // SDL_CONTROLLER_BUTTON_A..DPAD_RIGHT
            u32[1] = axisMask.toUInt()
            (d + 24)!!.reinterpret<COpaquePointerVar>().pointed.value = name.cstr.getPointer(this)
            attach.invoke(d)
        }
        if (index < 0) {
            log("virtual joystick attach failed ($index)")
            return index
        }
        virtualJoystick = open.invoke(index)
        log("virtual joystick attached index=$index opened=${virtualJoystick != null}")
        return if (virtualJoystick != null) index else -1
    }

    override fun onNativeJoy(deviceId: Int, axis: Int, value: Float) {
        val joystick = virtualJoystick ?: return
        setVirtualAxisSym.get()?.invoke(joystick, axis, (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
    }
}

private const val VIRTUAL_DESC_SIZE = 88
private const val STANDARD_BUTTONS = 15

/** SDL scancode for an Android key code, null when there is no equivalent. */
internal fun androidKeyToSdlScancode(keyCode: Int): Int? = when (keyCode) {
    in 29..54 -> 4 + (keyCode - 29)        // A..Z
    in 8..16 -> 30 + (keyCode - 8)         // 1..9
    7 -> 39                                // 0
    66 -> 40                               // Enter
    111 -> 41                              // Escape
    67 -> 42                               // Backspace
    61 -> 43                               // Tab
    62 -> 44                               // Space
    69 -> 45                               // Minus
    70 -> 46                               // Equals
    71 -> 47                               // [
    72 -> 48                               // ]
    73 -> 49                               // Backslash
    74 -> 51                               // ;
    75 -> 52                               // '
    68 -> 53                               // Grave
    55 -> 54                               // Comma
    56 -> 55                               // Period
    76 -> 56                               // Slash
    115 -> 57                              // Caps lock
    in 131..142 -> 58 + (keyCode - 131)    // F1..F12
    124 -> 73                              // Insert
    122 -> 74                              // Home
    92 -> 75                               // Page up
    112 -> 76                              // Forward delete
    123 -> 77                              // End
    93 -> 78                               // Page down
    22 -> 79                               // Right
    21 -> 80                               // Left
    20 -> 81                               // Down
    19 -> 82                               // Up
    113 -> 224                             // Left ctrl
    59 -> 225                              // Left shift
    57 -> 226                              // Left alt
    114 -> 228                             // Right ctrl
    60 -> 229                              // Right shift
    58 -> 230                              // Right alt
    else -> null
}
