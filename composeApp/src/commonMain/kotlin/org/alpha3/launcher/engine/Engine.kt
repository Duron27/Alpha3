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

    /**
     * The part of a game pixel the relative mouse motion has moved past [getMouseX] / [getMouseY] that SDL has not been
     * given yet (it takes whole pixels): added to them it is where the cursor really is, for drawing it smoothly.
     */
    fun mouseFractionX(): Float = 0f
    fun mouseFractionY(): Float = 0f

    /** True while the engine wants a visible mouse cursor (menus open). Polled every frame. */
    fun isMouseShown(): Boolean = false

    /**
     * Asks the running game for the benchmark (support/src/benchmark.cpp): it starts a few seconds later and takes
     * about two minutes. False where there is none.
     */
    fun startBenchmark(): Boolean = false

    /**
     * Asks the running game for the inspector's report (support/src/inspector.cpp): a frame is captured over the next
     * few frames. False where there is none.
     */
    fun requestInspector(): Boolean = false

    /** The inspector's report as JSON once it is made (handed over once); null until then. */
    fun inspectorReport(): String? = null

    /** The platform's own accounting of the process's memory, as label and value lines. */
    fun platformMemory(): List<Pair<String, String>> = emptyList()

    /**
     * Where the game's HUD elements are (support/src/hudlayout.cpp), for the overlay's edit mode: one line an element,
     * "name visible left top width height scale scales placed", in fractions of the screen. Null without a game.
     */
    fun hudLayout(): String? = null

    /** Puts a HUD element at [left], [top] (fractions of the screen) and [scale]; [keep] writes the layout file. */
    fun hudSet(name: String, left: Float, top: Float, scale: Float, keep: Boolean) {}

    /** Every HUD element back where the game puts it. */
    fun hudReset() {}
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
    override fun mouseFractionX() = bridge.mouseFractionX()
    override fun mouseFractionY() = bridge.mouseFractionY()
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
    override fun startBenchmark() = bridge.startBenchmark()
    override fun requestInspector() = bridge.requestInspector()
    override fun inspectorReport() = bridge.inspectorReport()
    override fun platformMemory() = bridge.platformMemory()
    override fun hudLayout() = bridge.hudLayout()
    override fun hudSet(name: String, left: Float, top: Float, scale: Float, keep: Boolean) =
        bridge.hudSet(name, left, top, scale, keep)
    override fun hudReset() = bridge.hudReset()
}
