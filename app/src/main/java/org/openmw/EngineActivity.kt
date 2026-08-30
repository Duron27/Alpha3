@file:OptIn(DelicateCoroutinesApi::class)

package org.openmw

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.libsdl.app.SDLActivity
import org.openmw.Constants.USER_OPENMW_CFG
import org.openmw.ui.controls.HapticEffect
import org.openmw.ui.controls.MouseIcon
import org.openmw.ui.controls.ResizableDraggableButton
import org.openmw.ui.controls.ResizableDraggableRightThumbstick
import org.openmw.ui.controls.ResizableDraggableThumbstick
import org.openmw.ui.controls.ScrollWheelIndicator
import org.openmw.ui.controls.UIKeyboard
import org.openmw.ui.controls.UIStateManager
import org.openmw.ui.controls.UIStateManager.configureControls
import org.openmw.ui.controls.UIStateManager.containerGlobalHeight
import org.openmw.ui.controls.UIStateManager.containerGlobalWidth
import org.openmw.ui.controls.UIStateManager.editMode
import org.openmw.ui.controls.UIStateManager.enableQuickSlot
import org.openmw.ui.controls.UIStateManager.enableRightThumb
import org.openmw.ui.controls.UIStateManager.gridAlpha
import org.openmw.ui.controls.UIStateManager.gridVisible
import org.openmw.ui.controls.UIStateManager.isCursorVisible
import org.openmw.ui.controls.UIStateManager.isRadialMenuExpanded
import org.openmw.ui.controls.UIStateManager.launchedActivity
import org.openmw.ui.controls.UIStateManager.offsetXFlow
import org.openmw.ui.controls.UIStateManager.offsetYFlow
import org.openmw.ui.controls.UIStateManager.soundHaptics
import org.openmw.ui.controls.UIStateManager.uqmJNI
import org.openmw.ui.controls.VirtualKeyboard
import org.openmw.ui.overlay.ExpandableCircleButton
import org.openmw.ui.overlay.GridOverlay
import org.openmw.ui.overlay.HiddenMenu
import org.openmw.ui.overlay.OverlayUI
import org.openmw.ui.view.BackgroundAnimation
import org.openmw.ui.view.addCustomLog
import org.openmw.ui.view.enableLogcat
import org.openmw.ui.view.vibrateHelper
import org.openmw.utils.DebugOverlayBox
import org.openmw.utils.GameFilesPreferences
import org.openmw.utils.GameFilesPreferences.getENVLine
import org.openmw.utils.GameFilesPreferences.loadAutoMouseMode
import org.openmw.utils.GameFilesPreferences.readAngle
import org.openmw.utils.GameFilesPreferences.readSPIRV
import org.openmw.utils.LibraryLoader
import org.openmw.utils.patchShadersLinking
import org.openmw.utils.patchShadersToGLES
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
@InternalCoroutinesApi
@AndroidEntryPoint
class EngineActivity : SDLActivity() {
    private lateinit var sdlView: View
    external fun getLastResourceName(): String
    external fun initAlpha3()
    external fun setNativeEnv(key: String, value: String)

    init {
        System.loadLibrary("Alpha3")
        setEnvironmentVariables(this@EngineActivity)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    override fun getLibraries(): Array<String> {
        return LibraryLoader.loadLibraries()
    }

    override fun getMainSharedObject(): String {
        return LibraryLoader.getMainSharedObject()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars(this) // fix sometimes systemBars will show again
    }

    override fun onDestroy() {
        super.onDestroy()
            finishAffinity() // kill all activity
            Process.killProcess(Process.myPid())

    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        runCatching {
            initAlpha3()
        }.onFailure {
            Log.e(TAG, "initAlpha3: ", it)
        }

        super.onCreate(savedInstanceState)

        launchedActivity = true
        val controllerConnected = isControllerConnected(this)

        setContentView(R.layout.engine_activity)

        if (!configureControls) {
            sdlView = getContentView()
        } else if (configureControls) {
            sdlView = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
        }

        hideSystemBars(this)
        enableScreenStayOn(this)

        // Add SDL view programmatically
        val sdlContainer = findViewById<FrameLayout>(R.id.sdl_container)
        (sdlView.parent as? ViewGroup)?.removeView(sdlView)
        sdlContainer.addView(sdlView) // Add SDL view to the sdl_container

        if (UIStateManager.tempCodeGroup == "OpenMW") {
            getPathToJni(filesDir.parent!!, Constants.USER_FILE_STORAGE)
        }

        if (UIStateManager.isLogcatEnabled) {
            enableLogcat()
        }

        // Setup Compose overlay for buttons
        val composeViewUI = findViewById<ComposeView>(R.id.compose_overlayUI)
        (composeViewUI.parent as? ViewGroup)?.removeView(composeViewUI)
        sdlContainer.addView(composeViewUI)

        // Adding a Global Layout Listener to get container dimensions
        sdlContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val containerWidth = sdlContainer.width.toFloat()
                val containerHeight = sdlContainer.height.toFloat()

                containerGlobalHeight.floatValue = sdlContainer.height.toFloat()
                containerGlobalWidth.floatValue = sdlContainer.width.toFloat()

                // Load button states into UIStateManager with container dimensions
                UIStateManager.loadButtonState(containerWidth, containerHeight)
                    // Adds Overlay menu for buttons and edit mode
                    composeViewUI.setContent {
                        patchShadersLinking()
                        patchShadersToGLES()

                        val isUIHidden by GameFilesPreferences.loadUIState(this@EngineActivity).collectAsState(initial = false)
                        val autoMouseMode by loadAutoMouseMode(this@EngineActivity).collectAsState(initial = "Hybrid")
                        val virtualKeyboard by GameFilesPreferences.useVirtualKeyboard(this@EngineActivity).collectAsState(initial = true)
                        val isVibrationOn by GameFilesPreferences.loadVibrationState(this@EngineActivity).collectAsState(initial = true)

                        BackHandler {
                            // disable back exit
                            Log.d(TAG, "onGlobalLayout: Back pressed")
                        }

                        if (UIStateManager.tempCodeGroup == "OpenMW") {
                            AutoMouseModeComposable()
                            SoundWatcher { name ->
                                val effect = findHapticForSound(name)
                                if (effect != null && isVibrationOn) {
                                    vibrateHelper(this@EngineActivity, effect.amplitude, effect.duration)
                                }
                                addCustomLog(
                                    "Sound: $name",
                                    textSize = 10,
                                    textColor = Color.White
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        // Consumes all touch events, effectively disabling them
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                            )
                        }
                        val snapX = remember { mutableStateOf<Float?>(null) }
                        val snapY = remember { mutableStateOf<Float?>(null) }

                        AnimatedVisibility(
                            visible = configureControls,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            BackgroundAnimation()
                        }

                        AnimatedVisibility(
                            visible = editMode && gridVisible.value,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            GridOverlay(
                                gridSize = UIStateManager.gridSize.intValue,
                                snapX = snapX.value,
                                snapY = snapY.value,
                                alpha = gridAlpha.floatValue
                            )
                        }

                        AnimatedVisibility(
                            visible = isCursorVisible == 1 && UIStateManager.tempCodeGroup == "OpenMW" && !isUIHidden,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            if (autoMouseMode != "None" || controllerConnected) {
                                MouseIcon(
                                    containerWidth = containerWidth,
                                    containerHeight = containerHeight
                                )
                            }
                        }

                        if (!isUIHidden) {
                            OverlayUI(
                                context = this@EngineActivity,
                                virtualKeyboard = virtualKeyboard,
                                onKeyEvent = { keyCode -> handleKeyEvent(keyCode) }
                            )
                        }

                        Buttons(context = this@EngineActivity, containerWidth = containerWidth, containerHeight = containerHeight)

                        AnimatedVisibility(
                            visible = enableRightThumb && UIStateManager.tempCodeGroup == "OpenMW",
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            ResizableDraggableRightThumbstick(
                                context = this@EngineActivity,
                                containerWidth = containerWidth,
                                containerHeight = containerHeight
                            )
                        }

                        AnimatedVisibility(
                            visible = UIKeyboard.showVKB,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            Box(modifier = Modifier.wrapContentSize()) {
                                VirtualKeyboard()
                            }
                        }

                        AnimatedVisibility(
                            visible = isCursorVisible == 0 && enableQuickSlot,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            HiddenMenu(
                                context = this@EngineActivity,
                                containerWidth = containerWidth,
                                containerHeight = containerHeight
                            )
                        }

                        AnimatedVisibility(
                            visible = isRadialMenuExpanded,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            Box(modifier = Modifier.wrapContentSize()) {
                                ExpandableCircleButton()
                            }
                        }

                        ScrollWheelIndicator(
                            context = this@EngineActivity,
                            containerWidth = containerWidth,
                            containerHeight = containerHeight
                        )

                        AnimatedVisibility(
                            visible = UIStateManager.tempCodeGroup == "UQM" && uqmJNI,
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            DebugOverlayBox()
                        }
                    }

                    val composeViewLeftThumb = findViewById<ComposeView>(R.id.compose_leftThumb)
                    (composeViewLeftThumb.parent as? ViewGroup)?.removeView(composeViewLeftThumb)

                    initializeOffsetsFromStateFlow()

                    composeViewLeftThumb.setContent {
                        ResizableDraggableThumbstick(
                            context = this@EngineActivity,
                            containerWidth = containerWidth,
                            containerHeight = containerHeight
                        )
                    }

                    sdlContainer.addView(composeViewLeftThumb)
                    updateComposeViewPosition(composeViewLeftThumb)

                // Remove the Global Layout Listener to prevent multiple calls
                sdlContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun initializeOffsetsFromStateFlow() {
        lifecycleScope.launch {
            val buttonStates = UIStateManager.buttonStates.value
            buttonStates[99]?.let { state ->
                offsetXFlow.value = state.offsetX
                offsetYFlow.value = state.offsetY
            }
        }
    }

    private fun updateComposeViewPosition(composeViewLeftThumb: ComposeView) {
        lifecycleScope.launch {
            launch {
                offsetXFlow.collect { offsetX ->
                    val params = composeViewLeftThumb.layoutParams as FrameLayout.LayoutParams
                    params.leftMargin = offsetX.roundToInt()
                    composeViewLeftThumb.layoutParams = params
                }
            }
            launch {
                offsetYFlow.collect { offsetY ->
                    val params = composeViewLeftThumb.layoutParams as FrameLayout.LayoutParams
                    params.topMargin = offsetY.roundToInt()
                    composeViewLeftThumb.layoutParams = params
                }
            }
        }
    }

    private fun setEnvironmentVariables(context: Context) {
        fun setEnv(key: String, value: String) {
            try {
                Os.setenv(key, value, true)
                setNativeEnv(key, value)
            } catch (e: Exception) {
                Log.e("Alpha3", "Failed setting env: $key=$value ($e)")
            }
        }

        try {
            val glesVersion = if (isEmulator()) "31" else "32"
            setEnv("OPENMW_GLES_VERSION", glesVersion)

            setEnv("UQM_CONFIG_DIR", "${Constants.USER_FILE_STORAGE}/uqm")

            if ((UIStateManager.tempCodeGroup) != "UQM") {
                val enableANGLE = runBlocking {
                    readAngle(context).first()
                }
                val enableSPIRV = runBlocking {
                    readSPIRV(context).first()
                }
                if (enableSPIRV) {
                    setEnv("OPENMW_SPIRV_SHADERCONV", "1")
                }
                if (enableANGLE) {
                    setEnv("OSG_USE_ANGLE", "1")
                    setEnv("SDL_VIDEO_GL_DRIVER", "libGLESv2_angle.so")
                    setEnv("SDL_VIDEO_EGL_DRIVER", "libEGL_angle.so")
                }
            }

            setEnv("OSG_TEXT_SHADER_TECHNIQUE", "ALL")
            setEnv("OSG_VERTEX_BUFFER_HINT", "VBO")

            setEnv("DETHRACE_ROOT_DIR", "${Constants.USER_FILE_STORAGE}/dethrace/")

            setEnv("HARNESS_OPENGL", "1")

            CoroutineScope(Dispatchers.IO).launch {
                GameFilesPreferences.readAvoid16Bits(context).collect { avoid16bits ->
                    if (avoid16bits) {
                        setEnv("LIBGL_AVOID16BITS", "1")
                    } else {
                        setEnv("LIBGL_AVOID16BITS", "0")
                    }
                }
                // Set LIBGL_SHRINK based on preference
                GameFilesPreferences.readTextureShrinkingOption(context)
                    .collect { textureShrinkingOption ->
                        when (textureShrinkingOption) {
                            "low" -> setEnv("LIBGL_SHRINK", "2")
                            "medium" -> setEnv("LIBGL_SHRINK", "7")
                            "high" -> setEnv("LIBGL_SHRINK", "6")
                            else -> setEnv("LIBGL_SHRINK", "0")
                        }
                    }
            }

            val envLine = getENVLine(context)
            envLine?.let {
                if (it.isNotEmpty()) {
                    // Split the command line string by commas to get individual key-value pairs
                    envLine.split(" ").forEach { pair ->
                        // Split each pair by '=' to separate key and value
                        val keyValue = pair.trim().split("=")
                        if (keyValue.size == 2) {
                            val key = keyValue[0].trim()
                            val value = keyValue[1].trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                setEnv(key, value)
                            }
                        }
                    }
                }
            }
        } catch (e: ErrnoException) {
            Log.e("Alpha3", "Failed setting environment variables.")
            e.printStackTrace()
        }

        Log.d("EngineActivity", "Environment variables set")
    }

    private fun handleKeyEvent(keyCode: Int) {
        val keyEventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val keyEventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        dispatchKeyEvent(keyEventDown)
        dispatchKeyEvent(keyEventUp)
        Log.d(TAG, "Sent key event: $keyCode")
    }

    companion object {
        private const val TAG = "OpenMW-Launcher"

        var resolutionX = 0
        var resolutionY = 0

        @JvmStatic
        @Suppress("KotlinJniMissingFunction", "LocalVariableName")
        external fun getPathToJni(path_global: String, path_user: String)
    }

    fun findHapticForSound(name: String): HapticEffect? {
        val lower = name.lowercase()
        return soundHaptics.entries.firstOrNull { (pattern, _) ->
            lower.contains(pattern)
        }?.value
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }

    @Composable
    fun SoundWatcher(onSound: (String) -> Unit) {
        var last by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos {
                    val name = getLastResourceName()
                    if (name.isNotEmpty() && name != last) {
                        last = name
                        onSound(name)
                    }
                }
            }
        }
    }

    override fun getArguments(): Array<String> {
        val commandLineArgs = runBlocking {
            GameFilesPreferences.getARGLine(this@EngineActivity).first() ?: ""
        }

        return try {
            val resourcesPath = "--resources " + Constants.USER_RESOURCES
            val combinedArgs = if (commandLineArgs.isNotEmpty()) {
                "$resourcesPath $commandLineArgs"
            } else {
                resourcesPath
            }

            // Parse the combined arguments
            val args = arrayListOf<String>()
            combinedArgs.split(" ".toRegex()).forEach {
                if (it.isNotEmpty()) {
                    args.add(it)
                }
            }
            args.toTypedArray()
        } catch (_: Exception) {
            super.getArguments()
        }
    }
}

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun AutoMouseModeComposable() {
    var isMouseShown by remember { mutableIntStateOf(SDLActivity.isMouseShown()) }
    // Launch a Choreographer callback to update isMouseShown in real-time
    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                isMouseShown = SDLActivity.isMouseShown()
                isCursorVisible = isMouseShown
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(frameCallback)

        onDispose {
            choreographer.removeFrameCallback(frameCallback)
        }
    }
}

@Composable
fun Buttons(context: Context, containerWidth: Float, containerHeight: Float) {
    val buttonStates by UIStateManager.buttonStates.collectAsState()

    buttonStates.values
        .filter { it.id !in listOf(99, 98, 101, 201) }
        .forEach { button ->
            ResizableDraggableButton(
                context = context,
                id = button.id,
                keyCode = button.keyCode,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                onDelete = {
                    UIStateManager.removeButtonState(button.id, containerWidth, containerHeight)
                }
            )
        }
}

fun showSystemBars(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
    } else {
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
}


fun hideSystemBars(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}

@OptIn(InternalCoroutinesApi::class)
fun runIniImporter(context: Context, gameFilesPath: String) {
    try {
        val intent = Intent(context, org.openmw.utils.IniImporterService::class.java).apply {
            putExtra("gameFilesPath", gameFilesPath)
            putExtra("configPath", USER_OPENMW_CFG)
        }
        context.startService(intent)
        Log.d("EngineActivity", "IniImporter Service started in separate process with path: $gameFilesPath")
    } catch (e: Exception) {
        Log.e("EngineActivity", "Failed to start IniImporter Service", e)
    }
}

@OptIn(InternalCoroutinesApi::class)
fun runNavMesh(context: Context) {
    try {
        val intent = Intent(context, org.openmw.utils.NavMeshService::class.java).apply {
            putExtra("configPath", Constants.USER_CONFIG)
        }
        context.startService(intent)
        Log.d("EngineActivity", "NavMesh Service started")
    } catch (e: Exception) {
        Log.e("EngineActivity", "Failed to start NavMesh Service", e)
    }
}

fun enableScreenStayOn(activity: Activity) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

fun disableScreenStayOn(activity: Activity) {
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
