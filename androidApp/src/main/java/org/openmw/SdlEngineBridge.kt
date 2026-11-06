package org.openmw

import android.app.ActivityManager
import android.content.Context
import android.hardware.input.InputManager
import android.os.Debug
import android.view.InputDevice.SOURCE_GAMEPAD
import android.view.KeyCharacterMap
import org.alpha3.launcher.engine.EngineBridge
import org.alpha3.launcher.ui.controls.UIStateManager
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLControllerManager

/** Android implementation of the shared [EngineBridge]: forwards straight to SDL's JNI entry points. */
class SdlEngineBridge(private val context: Context) : EngineBridge {
    override fun onNativeKeyDown(keyCode: Int) = SDLActivity.onNativeKeyDown(keyCode)
    override fun onNativeKeyUp(keyCode: Int) = SDLActivity.onNativeKeyUp(keyCode)
    override fun onNativeMouse(button: Int, action: Int, x: Float, y: Float, relative: Boolean) =
        SDLActivity.onNativeMouse(button, action, x, y, relative)
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
