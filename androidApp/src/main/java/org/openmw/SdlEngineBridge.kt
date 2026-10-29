package org.openmw

import android.content.Context
import android.hardware.input.InputManager
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

    override fun isControllerConnected(): Boolean {
        if (UIStateManager.ignoreController) return false
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        return inputManager.inputDeviceIds.any { id ->
            inputManager.getInputDevice(id)?.sources?.and(SOURCE_GAMEPAD) == SOURCE_GAMEPAD
        }
    }
}
