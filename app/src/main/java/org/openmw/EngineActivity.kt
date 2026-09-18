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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.libsdl.app.SDLActivity
import org.openmw.Constants.USER_OPENMW_CFG
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
import org.openmw.utils.LibraryLoader
import org.openmw.utils.dataStore
import org.openmw.utils.patchShadersLinking
import org.openmw.utils.patchShadersToGLES
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Suppress("DEPRECATION")
@InternalCoroutinesApi
@AndroidEntryPoint
class EngineActivity : SDLActivity() {
    private lateinit var sdlView: View
    external fun initAlpha3()
    external fun setNativeEnv(key: String, value: String)
    external fun omwIsGameRunning(): Boolean
    //external fun omwQuitGame()
    external fun omwGetPlayerHealthCurrent(): Float
    external fun omwGetPlayerHealthModified(): Float
    external fun omwGetPlayerHealthBase(): Float
    external fun omwGetPlayerMagickaCurrent(): Float
    external fun omwGetPlayerMagickaModified(): Float
    external fun omwGetPlayerFatigueCurrent(): Float
    external fun omwGetPlayerFatigueModified(): Float
    external fun omwGetPlayerLevel(): Int
    external fun omwGetPlayerX(): Float
    external fun omwGetPlayerY(): Float
    external fun omwGetPlayerZ(): Float
    external fun omwGetPlayerCellName(): String
    external fun omwGetPlayerInventorySummary(): String
    external fun omwGetDrawState(): Int
    external fun omwGetSelectedWeaponName(): String
    external fun omwGetSelectedSpellId(): String

    init {
        System.loadLibrary("Alpha3")
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
        setEnvironmentVariables(this@EngineActivity)

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

                        val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(this@EngineActivity, GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
                        val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
                        val autoMouseMode by GameFilesPreferences.getPreference(this@EngineActivity, GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid").collectAsState(initial = "Hybrid")
                        val virtualKeyboard by GameFilesPreferences.getPreference(this@EngineActivity, GameFilesPreferences.USE_VIRTUAL_KB, true).collectAsState(initial = true)
                        val isVibrationOnFlow = remember { GameFilesPreferences.getPreference(this@EngineActivity, GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
                        val isVibrationOn by isVibrationOnFlow.collectAsState(initial = true)

                        BackHandler {
                            // disable back exit
                            Log.d(TAG, "onGlobalLayout: Back pressed")
                        }

                        if (UIStateManager.tempCodeGroup == "OpenMW") {
                            AutoMouseModeComposable()
                            //OpenMWDebugStatsOverlay(this@EngineActivity)
                            
                            DamageWatcher(this@EngineActivity) { damage ->
                                Log.d(TAG, "Player took $damage damage!")
                                addCustomLog(
                                    "Ouch! Took $damage damage",
                                    textSize = 10,
                                    textColor = Color.Red
                                )
                                
                                if (isVibrationOn) {
                                    // Scale amplitude from 50 (light hit) up to 255 (massive hit)
                                    val amplitude = (damage * 8).toInt().coerceIn(50, 255)
                                    // Scale duration from 100ms up to 300ms
                                    val duration = (damage * 5).toLong().coerceIn(100L, 300L)
                                    
                                    vibrateHelper(this@EngineActivity, amplitude, duration)
                                }
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
            val envLine = runBlocking {
                val prefs = context.dataStore.data.first()
                val existing = prefs[GameFilesPreferences.ENV_LINE_KEY]
                val enableANGLE = prefs[GameFilesPreferences.USE_ANGLE] == true
                val enableZINK = prefs[GameFilesPreferences.USE_ZINK] == true
                val enableSPIRV = prefs[GameFilesPreferences.USE_SPIRV] == true
                if (enableSPIRV) {
                    setEnv("OPENMW_SPIRV_SHADERCONV", "1")
                }
                if (enableZINK) {
                    setEnv("OSG_USE_ZINK", "1")
                    setEnv("EGL_PLATFORM", "android")
                    setEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink")
                    setEnv("SDL_VIDEO_GL_DRIVER", "libGLESv2_zink.so")
                    setEnv("SDL_VIDEO_EGL_DRIVER", "libEGL_zink.so")
                    setEnv("MESA_GLES_VERSION_OVERRIDE", "3.2")
                    setEnv("MESA_GLSL_VERSION_OVERRIDE", "320")
                    setEnv("NATIVE_LIB_DIR", applicationInfo.nativeLibraryDir)
                    setEnv("TMPDIR", cacheDir.absolutePath)
                    setEnv("MESA_LOG_FILE", Constants.USER_FILE_STORAGE + "/config/mesa.log")
                } else if (enableANGLE) {
                    setEnv("OSG_USE_ANGLE", "1")
                    setEnv("SDL_VIDEO_GL_DRIVER", "libGLESv2_angle.so")
                    setEnv("SDL_VIDEO_EGL_DRIVER", "libEGL_angle.so")
                }
                if (!existing.isNullOrBlank()) {
                    existing
                } else {
                    val glesVersion = if (isEmulator()) "31" else "32"
                    val defaults = mutableListOf<String>()
                    defaults.add("OPENMW_GLES_VERSION=$glesVersion")
                    defaults.add("UQM_CONFIG_DIR=${Constants.USER_FILE_STORAGE}/uqm")
                    defaults.add("OSG_TEXT_SHADER_TECHNIQUE=ALL")
                    defaults.add("OSG_VERTEX_BUFFER_HINT=VBO")
                    defaults.add("DETHRACE_ROOT_DIR=${Constants.USER_FILE_STORAGE}/dethrace/")
                    defaults.add("HARNESS_OPENGL=1")

                    val avoid16 = prefs[GameFilesPreferences.AVOID_16_BITS_KEY] == true
                    defaults.add(if (avoid16) "LIBGL_AVOID16BITS=1" else "LIBGL_AVOID16BITS=0")

                    val shrink = prefs[GameFilesPreferences.TEXTURE_SHRINKING_KEY] ?: "0"
                    val shrinkVal = when (shrink) {
                        "low" -> "2"
                        "medium" -> "7"
                        "high" -> "6"
                        else -> "0"
                    }
                    defaults.add("LIBGL_SHRINK=$shrinkVal")

                    val defaultStr = defaults.joinToString(" ")
                    GameFilesPreferences.setPreference(context, GameFilesPreferences.ENV_LINE_KEY, defaultStr)
                    defaultStr
                }
            }
            if (envLine.isNotEmpty()) {
                envLine.split("\\s+".toRegex()).forEach { pair ->
                    val keyValue = pair.trim().split("=", limit = 2)
                    if (keyValue.size == 2) {
                        val key = keyValue[0].trim()
                        val value = keyValue[1].trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            setEnv(key, value)
                        }
                    }
                }
            }
        } catch (e: ErrnoException) {
            Log.e("Alpha3", "Failed setting environment variables.")
            e.printStackTrace()
        }

        Log.d("EngineActivity", "Environment variables set dynamically from DataStore")
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

    @Composable
    fun DamageWatcher(activity: EngineActivity, onDamageTaken: (amount: Float) -> Unit) {
        var lastHealth by remember { mutableFloatStateOf(-1f) }

        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos {
                    runCatching {
                        if (activity.omwIsGameRunning()) {
                            val currentHealth = activity.omwGetPlayerHealthCurrent()
                            
                            // Initialize lastHealth if this is the first frame
                            if (lastHealth == -1f) {
                                lastHealth = currentHealth
                            } else {
                                // If current health is less than last frame's health, we took damage!
                                if (currentHealth < lastHealth) {
                                    val damageAmount = lastHealth - currentHealth
                                    onDamageTaken(damageAmount)
                                }
                                lastHealth = currentHealth
                            }
                        } else {
                            lastHealth = -1f // Reset when game is not running
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun OpenMWDebugStatsOverlay(activity: EngineActivity) {
        var isRunning by remember { mutableStateOf(false) }
        var healthCur by remember { mutableFloatStateOf(0f) }
        var healthMod by remember { mutableFloatStateOf(0f) }
        var healthBase by remember { mutableFloatStateOf(0f) }
        var magickaCur by remember { mutableFloatStateOf(0f) }
        var magickaMod by remember { mutableFloatStateOf(0f) }
        var fatigueCur by remember { mutableFloatStateOf(0f) }
        var fatigueMod by remember { mutableFloatStateOf(0f) }
        var level by remember { mutableIntStateOf(0) }
        var posX by remember { mutableFloatStateOf(0f) }
        var posY by remember { mutableFloatStateOf(0f) }
        var posZ by remember { mutableFloatStateOf(0f) }
        var cellName by remember { mutableStateOf("") }
        var inventorySummary by remember { mutableStateOf("") }
        var drawState by remember { mutableIntStateOf(0) }
        var weaponName by remember { mutableStateOf("") }
        var spellId by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            while (true) {
                runCatching {
                    isRunning = activity.omwIsGameRunning()
                    if (isRunning) {
                        healthCur = activity.omwGetPlayerHealthCurrent()
                        healthMod = activity.omwGetPlayerHealthModified()
                        healthBase = activity.omwGetPlayerHealthBase()
                        magickaCur = activity.omwGetPlayerMagickaCurrent()
                        magickaMod = activity.omwGetPlayerMagickaModified()
                        fatigueCur = activity.omwGetPlayerFatigueCurrent()
                        fatigueMod = activity.omwGetPlayerFatigueModified()
                        level = activity.omwGetPlayerLevel()
                        posX = activity.omwGetPlayerX()
                        posY = activity.omwGetPlayerY()
                        posZ = activity.omwGetPlayerZ()
                        cellName = activity.omwGetPlayerCellName()
                        inventorySummary = activity.omwGetPlayerInventorySummary()
                        drawState = activity.omwGetDrawState()
                        weaponName = activity.omwGetSelectedWeaponName()
                        spellId = activity.omwGetSelectedSpellId()
                    }

                }
                delay(1000.milliseconds)
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start
            ) {
                Text("=== OpenMW Stats ===", color = Color.Green, fontSize = 14.sp)
                Text("Game Running: $isRunning", color = Color.White, fontSize = 12.sp)
                Text("Health: ${healthCur.toInt()} / ${healthMod.toInt()} (Base: ${healthBase.toInt()})", color = Color.White, fontSize = 12.sp)
                Text("Magicka: ${magickaCur.toInt()} / ${magickaMod.toInt()}", color = Color.White, fontSize = 12.sp)
                Text("Fatigue: ${fatigueCur.toInt()} / ${fatigueMod.toInt()}", color = Color.White, fontSize = 12.sp)
                Text("Level: $level", color = Color.White, fontSize = 12.sp)
                Text("Pos: X:%.1f Y:%.1f Z:%.1f".format(posX, posY, posZ), color = Color.White, fontSize = 12.sp)
                Text("Cell: $cellName", color = Color.White, fontSize = 12.sp)
                Text("Draw State: $drawState", color = Color.White, fontSize = 12.sp)
                Text("Weapon: $weaponName", color = Color.White, fontSize = 12.sp)
                Text("Spell: $spellId", color = Color.White, fontSize = 12.sp)
                Text("Inventory: $inventorySummary", color = Color.White, fontSize = 10.sp)
            }
        }
    }

    override fun getArguments(): Array<String> {
        val commandLineArgs = runBlocking {
            GameFilesPreferences.getNullablePreference(this@EngineActivity, GameFilesPreferences.ARG_LINE_KEY).first() ?: ""
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
    val window = activity.window
    WindowCompat.getInsetsController(window, window.decorView).show(
        WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
    )
}

fun hideSystemBars(activity: Activity) {
    val window = activity.window
    WindowCompat.getInsetsController(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
