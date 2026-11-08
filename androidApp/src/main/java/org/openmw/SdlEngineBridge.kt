package org.openmw

import android.app.ActivityManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice.SOURCE_GAMEPAD
import android.view.KeyCharacterMap
import org.alpha3.launcher.engine.EngineBridge
import org.alpha3.launcher.ui.controls.UIStateManager
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.exp

/** Android implementation of the shared [EngineBridge]: forwards straight to SDL's JNI entry points. */
class SdlEngineBridge(private val context: Context) : EngineBridge {
    override fun onNativeKeyDown(keyCode: Int) = SDLActivity.onNativeKeyDown(keyCode)
    override fun onNativeKeyUp(keyCode: Int) = SDLActivity.onNativeKeyUp(keyCode)
    override fun onNativeMouse(button: Int, action: Int, x: Float, y: Float, relative: Boolean) {
        if (relative && action == MOVE) {
            smoothMotion(button, x, y)
        } else {
            flushMotion()  // a click lands where the motion so far has taken the cursor
            SDLActivity.onNativeMouse(button, action, x, y, relative)
        }
    }

    // The thumbstick, dynamic buttons and touch areas turn the finger's moves straight into mouse motion at about 2 game
    // pixels per screen pixel. Touch panels report a slow drag in whole-pixel steps, x and y in different reports tens of
    // milliseconds apart (seen in getevent on the SM-T720), so a slow diagonal came out as a staircase of x and y jumps.
    // The motion is summed into rawX/Y and followed each display frame by a 1€ filter (Casiez et al.): strong smoothing
    // while the finger is slow, where the steps show, almost none when it is fast, so flicks do not lag. Nothing is lost:
    // the filtered position ends on the finger's, and a click first sends whatever is still waiting.
    private var rawX = 0f
    private var rawY = 0f
    private var filtX = 0f
    private var filtY = 0f
    private var prevRawX = 0f
    private var prevRawY = 0f
    private var speed = 0f
    private var pendingButton = 0
    private var lastFrameNs = 0L
    private var pumping = false
    private val choreographer by lazy { Choreographer.getInstance() }

    private val pump = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastFrameNs == 0L) FRAME_S
                else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastFrameNs = frameTimeNanos
            // the finger's speed (game pixels a second), itself smoothed, sets how much the position is smoothed
            val v = hypot(rawX - prevRawX, rawY - prevRawY) / dt
            prevRawX = rawX
            prevRawY = rawY
            speed += alpha(dt, SPEED_CUTOFF_HZ) * (v - speed)
            val a = alpha(dt, MIN_CUTOFF_HZ + BETA * speed)
            val nx = filtX + a * (rawX - filtX)
            val ny = filtY + a * (rawY - filtY)
            relativeMotion(pendingButton, nx - filtX, ny - filtY)
            filtX = nx
            filtY = ny
            if (abs(rawX - filtX) < 0.05f && abs(rawY - filtY) < 0.05f && speed < 1f) flushMotion()
            else choreographer.postFrameCallback(this)
        }
    }

    private fun alpha(dt: Float, cutoffHz: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    // Pointer acceleration while OpenMW's cursor is shown: the controls hand over the finger's moves at a fixed ~2 game
    // pixels per screen pixel, so the finger's own small wobble (a slow diagonal is runs of 3 to 5 one-pixel steps on one
    // axis, then the other) came out as stairs four times its size. Like a trackpad: slow moves get less gain (precise,
    // the wobble stays small), fast ones more (crossing the screen stays quick). Looking around keeps the plain gain.
    private var inSpeed = 0f
    private var lastInputNs = 0L

    private fun accelerate(dx: Float, dy: Float): Float {
        if (UIStateManager.isCursorVisible == 0) return 1f
        val now = SystemClock.uptimeNanos()
        val dt = if (lastInputNs == 0L) FRAME_S else ((now - lastInputNs) / 1e9f).coerceIn(0.004f, 0.1f)
        lastInputNs = now
        val v = hypot(dx, dy) / dt
        inSpeed += (1f - exp(-dt / 0.05f)) * (v - inSpeed)  // input speed, game pixels a second, over ~50 ms
        val t = ((inSpeed - ACCEL_SLOW) / (ACCEL_FAST - ACCEL_SLOW)).coerceIn(0f, 1f)
        return GAIN_SLOW + (GAIN_FAST - GAIN_SLOW) * t * t * (3f - 2f * t)
    }

    private fun smoothMotion(button: Int, dx0: Float, dy0: Float) {
        // Choreographer belongs to the main thread; anything else goes straight through
        if (Looper.myLooper() != Looper.getMainLooper()) return relativeMotion(button, dx0, dy0)
        val gain = accelerate(dx0, dy0)
        val dx = dx0 * gain
        val dy = dy0 * gain
        rawX += dx
        rawY += dy
        pendingButton = button
        if (!pumping) {
            pumping = true
            lastFrameNs = 0L
            speed = 0f
            prevRawX = rawX - dx
            prevRawY = rawY - dy
            choreographer.postFrameCallback(pump)
        }
    }

    /** Sends what is still waiting at once and stops the frame pump. */
    private fun flushMotion() {
        if (pumping && Looper.myLooper() == Looper.getMainLooper()) {
            choreographer.removeFrameCallback(pump)
            pumping = false
        }
        val x = rawX - filtX
        val y = rawY - filtY
        rawX = 0f; rawY = 0f; filtX = 0f; filtY = 0f
        if (x != 0f || y != 0f) relativeMotion(pendingButton, x, y)
    }

    // SDL2's Android_OnMouse casts motion to int, which drops the fraction of every delta: slow drags lose most of
    // their movement and fast ones stutter. Keep the remainder and send whole pixels, as the iOS bridge does.
    private var carryX = 0f
    private var carryY = 0f

    private fun relativeMotion(button: Int, dx: Float, dy: Float) {
        val fx = dx + carryX
        val fy = dy + carryY
        val ix = fx.toInt()
        val iy = fy.toInt()
        carryX = fx - ix
        carryY = fy - iy
        if (ix != 0 || iy != 0) SDLActivity.onNativeMouse(button, MOVE, ix.toFloat(), iy.toFloat(), true)
    }

    private companion object {
        const val MOVE = 2
        const val FRAME_S = 1f / 60f
        const val MIN_CUTOFF_HZ = 1.0f   // slow drags: about 160 ms of smoothing, which hides the panel's steps
        const val GAIN_SLOW = 0.45f      // cursor gain at or under ACCEL_SLOW game pixels a second of input
        const val GAIN_FAST = 1.4f       // and at or over ACCEL_FAST, eased between
        const val ACCEL_SLOW = 150f
        const val ACCEL_FAST = 1500f
        const val BETA = 0.02f           // + this many Hz per game pixel a second: a 1000 px/s flick is at 21.5 Hz
        const val SPEED_CUTOFF_HZ = 2f
    }
    override fun mouseFractionX() = carryX
    override fun mouseFractionY() = carryY
    override fun getMouseX() = SDLActivity.getMouseX()
    override fun getMouseY() = SDLActivity.getMouseY()
    override fun nativeCommitText(text: String, newCursorPosition: Int) =
        SDLActivity.nativeCommitText(text, newCursorPosition)

    override fun nativeAddJoystick(
        deviceId: Int, name: String, desc: String, vendorId: Int, productId: Int,
        isAccelerometer: Boolean, buttonMask: Int, nAxes: Int, axisMask: Int, nHats: Int, nBalls: Int
    ) = SDLControllerManager.nativeAddJoystick(
        deviceId, name, desc, vendorId, productId, isAccelerometer, buttonMask, nAxes, axisMask, nHats, nBalls
    )
    override fun onNativeJoy(deviceId: Int, axis: Int, value: Float) =
        SDLControllerManager.onNativeJoy(deviceId, axis, value)

    private val keyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    override fun keyCodeForChar(char: String): Int? =
        keyCharacterMap.getEvents(char.toCharArray())?.firstOrNull()?.keyCode

    // in libopenmw_upstream (support/src/benchmark.cpp), there once the game has started
    private external fun omwBenchmarkStart()

    override fun startBenchmark(): Boolean =
        runCatching { omwBenchmarkStart() }.isSuccess

    // in libopenmw_upstream (support/src/inspector.cpp)
    private external fun omwInspectorRequest(): Boolean
    private external fun omwInspectorReport(): ByteArray?

    override fun requestInspector(): Boolean =
        runCatching { omwInspectorRequest() }.getOrDefault(false)

    override fun inspectorReport(): String? =
        runCatching { omwInspectorReport()?.decodeToString() }.getOrNull()

    // in libopenmw_upstream (support/src/hudlayout.cpp)
    private external fun omwHudLayout(): ByteArray?
    private external fun omwHudSet(name: String, left: Float, top: Float, scale: Float, keep: Boolean)
    private external fun omwHudReset()

    override fun hudLayout(): String? = runCatching { omwHudLayout()?.decodeToString() }.getOrNull()

    override fun hudSet(name: String, left: Float, top: Float, scale: Float, keep: Boolean) {
        runCatching { omwHudSet(name, left, top, scale, keep) }
    }

    override fun hudReset() {
        runCatching { omwHudReset() }
    }

    override fun platformMemory(): List<Pair<String, String>> {
        val lines = mutableListOf<Pair<String, String>>()
        fun mb(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
        runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            // Android's own split of the process (what dumpsys meminfo shows): graphics is what the GPU driver holds
            for ((key, value) in info.memoryStats.toSortedMap()) {
                val kb = value.toLongOrNull()
                lines += key.removePrefix("summary.") to (if (kb != null) mb(kb * 1024) else value)
            }
        }
        lines += "native heap allocated" to mb(Debug.getNativeHeapAllocatedSize())
        lines += "native heap size" to mb(Debug.getNativeHeapSize())
        val runtime = Runtime.getRuntime()
        lines += "Java heap used" to mb(runtime.totalMemory() - runtime.freeMemory())
        lines += "Java heap limit" to mb(runtime.maxMemory())
        runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memory)
            lines += "device RAM" to mb(memory.totalMem)
            lines += "device RAM available" to mb(memory.availMem)
            lines += "low memory threshold" to mb(memory.threshold)
            lines += "low on memory now" to memory.lowMemory.toString()
            lines += "app memory class" to "${activityManager.memoryClass} MB (large ${activityManager.largeMemoryClass} MB)"
        }
        return lines
    }

    override fun isControllerConnected(): Boolean {
        if (UIStateManager.ignoreController) return false
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        return inputManager.inputDeviceIds.any { id ->
            inputManager.getInputDevice(id)?.sources?.and(SOURCE_GAMEPAD) == SOURCE_GAMEPAD
        }
    }
}
