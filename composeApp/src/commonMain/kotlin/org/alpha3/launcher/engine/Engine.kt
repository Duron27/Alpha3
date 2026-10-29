package org.alpha3.launcher.engine

/** Mouse actions passed to [Engine.onNativeMouse] (same values as Android's MotionEvent). */
object MouseActions {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_SCROLL = 8
}

/** What the shared on-screen controls need from the running OpenMW/SDL instance. */
interface EngineBridge {
    fun onNativeKeyDown(keyCode: Int)
    fun onNativeKeyUp(keyCode: Int)
    fun onNativeMouse(button: Int, action: Int, x: Float, y: Float, relative: Boolean)
    fun getMouseX(): Int
    fun getMouseY(): Int
    fun nativeCommitText(text: String, newCursorPosition: Int)
    fun isControllerConnected(): Boolean
    fun nativeAddJoystick(
        deviceId: Int, name: String, desc: String, vendorId: Int, productId: Int,
        isAccelerometer: Boolean, buttonMask: Int, nAxes: Int, axisMask: Int, nHats: Int, nBalls: Int
    ): Int
    fun onNativeJoy(deviceId: Int, axis: Int, value: Float)

    /** Key code (see [KeyCodes]) that types [char] on a virtual keyboard, null when there is none. */
    fun keyCodeForChar(char: String): Int?

    /** True while the engine wants a visible mouse cursor (menus open). Polled every frame. */
    fun isMouseShown(): Boolean = false
}

private object NoEngine : EngineBridge {
    override fun onNativeKeyDown(keyCode: Int) {}
    override fun onNativeKeyUp(keyCode: Int) {}
    override fun onNativeMouse(button: Int, action: Int, x: Float, y: Float, relative: Boolean) {}
    override fun getMouseX() = 0
    override fun getMouseY() = 0
    override fun nativeCommitText(text: String, newCursorPosition: Int) {}
    override fun isControllerConnected() = false
    override fun nativeAddJoystick(
        deviceId: Int, name: String, desc: String, vendorId: Int, productId: Int,
        isAccelerometer: Boolean, buttonMask: Int, nAxes: Int, axisMask: Int, nHats: Int, nBalls: Int
    ) = -1
    override fun onNativeJoy(deviceId: Int, axis: Int, value: Float) {}
    override fun keyCodeForChar(char: String): Int? = null
    override fun isMouseShown() = false
}

/**
 * Entry point for shared code. The platform installs its implementation in [bridge]
 * (Android: SDLActivity JNI, iOS: the SDL C entry points) before the engine starts.
 */
object Engine : EngineBridge {
    var bridge: EngineBridge = NoEngine

    /** Size of the SDL surface in pixels, set by whoever configures the engine. */
    var resolutionX = 0
    var resolutionY = 0

    /** Delivers a full key press (down + up) through the host, installed by the engine host. */
    var sendKeyEvent: (Int) -> Unit = {}

    override fun onNativeKeyDown(keyCode: Int) = bridge.onNativeKeyDown(keyCode)
    override fun onNativeKeyUp(keyCode: Int) = bridge.onNativeKeyUp(keyCode)
    override fun onNativeMouse(button: Int, action: Int, x: Float, y: Float, relative: Boolean) =
        bridge.onNativeMouse(button, action, x, y, relative)
    override fun getMouseX() = bridge.getMouseX()
    override fun getMouseY() = bridge.getMouseY()
    override fun nativeCommitText(text: String, newCursorPosition: Int) =
        bridge.nativeCommitText(text, newCursorPosition)
    override fun isControllerConnected() = bridge.isControllerConnected()
    override fun nativeAddJoystick(
        deviceId: Int, name: String, desc: String, vendorId: Int, productId: Int,
        isAccelerometer: Boolean, buttonMask: Int, nAxes: Int, axisMask: Int, nHats: Int, nBalls: Int
    ) = bridge.nativeAddJoystick(
        deviceId, name, desc, vendorId, productId, isAccelerometer, buttonMask, nAxes, axisMask, nHats, nBalls
    )
    override fun onNativeJoy(deviceId: Int, axis: Int, value: Float) = bridge.onNativeJoy(deviceId, axis, value)
    override fun keyCodeForChar(char: String): Int? = bridge.keyCodeForChar(char)
    override fun isMouseShown() = bridge.isMouseShown()
}
