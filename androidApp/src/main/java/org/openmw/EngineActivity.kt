@file:OptIn(DelicateCoroutinesApi::class)

package org.openmw

import org.alpha3.launcher.paths.OpenMWPaths
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.libsdl.app.SDLActivity
import org.alpha3.launcher.paths.OpenMWPaths.USER_OPENMW_CFG
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.ui.controls.MouseIcon
import org.alpha3.launcher.ui.controls.ResizableDraggableButton
import org.alpha3.launcher.ui.controls.ResizableDraggableRightThumbstick
import org.alpha3.launcher.ui.controls.ResizableDraggableThumbstick
import org.alpha3.launcher.ui.controls.ScrollWheelIndicator
import org.alpha3.launcher.ui.controls.UIKeyboard
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.controls.UIStateManager.configureControls
import org.alpha3.launcher.ui.controls.UIStateManager.containerGlobalHeight
import org.alpha3.launcher.ui.controls.UIStateManager.containerGlobalWidth
import org.alpha3.launcher.ui.controls.UIStateManager.editMode
import org.alpha3.launcher.ui.controls.UIStateManager.enableQuickSlot
import org.alpha3.launcher.ui.controls.UIStateManager.enableRightThumb
import org.alpha3.launcher.ui.controls.UIStateManager.gridAlpha
import org.alpha3.launcher.ui.controls.UIStateManager.gridVisible
import org.alpha3.launcher.ui.controls.UIStateManager.isCursorVisible
import org.alpha3.launcher.ui.controls.UIStateManager.isRadialMenuExpanded
import org.alpha3.launcher.ui.controls.UIStateManager.launchedActivity
import org.alpha3.launcher.ui.controls.VirtualKeyboard
import org.alpha3.launcher.ui.overlay.ExpandableCircleButton
import org.alpha3.launcher.ui.overlay.GridOverlay
import org.alpha3.launcher.ui.overlay.HiddenMenu
import org.alpha3.launcher.ui.overlay.OverlayUI
import org.alpha3.launcher.ui.view.BackgroundAnimation
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.ui.view.enableLogcat
import org.alpha3.launcher.ui.view.vibrateHelper
import org.alpha3.launcher.launch.LaunchArguments
import org.alpha3.launcher.launch.LaunchEnvironment
import org.alpha3.launcher.launch.tokenizeArguments
import org.alpha3.launcher.utils.DriverDebugEnvs
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.GpuDrivers
import org.openmw.utils.AndroidGpuDriverManager
import org.openmw.utils.LibraryLoader
import org.openmw.utils.UpstreamEngine
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.utils.dataStore
import kotlin.time.Duration.Companion.milliseconds

@Suppress("DEPRECATION")
@InternalCoroutinesApi
@AndroidEntryPoint
class EngineActivity : SDLActivity() {
    private val engineStartedAt = System.currentTimeMillis()

    private lateinit var sdlView: View
    external fun initAlpha3()
    external fun setNativeEnv(key: String, value: String)
    external fun initVulkanDriver(enabled: Boolean, nativeLibDir: String, tmpLibDir: String, driverDir: String, driverName: String, bundledFallback: Boolean): Boolean
    external fun omwIsGameRunning(): Boolean
    external fun omwGetFrameTimes(): FloatArray
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
    external fun omwGetSelectedEnchantItemName(): String

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

        // Custom Vulkan driver via libadrenotools (Turnip on Adreno, PanVK on Mali); must happen before the first Vulkan
        // call. The setting turns it off, and it falls back to the device driver if it can't be loaded.
        runCatching {
            val prefs = runBlocking { dataStore.data.first() }
            // the bundled Turnip only runs on Adreno: elsewhere the switch starts off, and "bundled" means the device driver
            val family = AndroidGpuDriverManager.detectGpuFamily()
            val adreno = family != org.alpha3.launcher.utils.GpuFamily.MALI
            val selected = prefs[GameFilesPreferences.SELECTED_GPU_DRIVER] ?: GpuDrivers.BUILTIN_ID
            val custom = AndroidGpuDriverManager.resolve(this, selected)
            val useFreedreno = (prefs[GameFilesPreferences.USE_FREEDRENO] ?: adreno) && (adreno || custom != null)
            if (useFreedreno) {
                // Debug toggles from the dev settings; the driver reads these when it loads.
                val debugEnvs = prefs[GameFilesPreferences.DRIVER_DEBUG_ENVS].orEmpty().split(",").filter { it.isNotEmpty() }.toSet()
                DriverDebugEnvs.toEnvMap(debugEnvs).forEach { (key, value) ->
                    Os.setenv(key, value, true)
                    Log.i(TAG, "Driver env: $key=$value")
                }
            }
            // A driver downloaded in the settings, or the bundled Turnip when none (or a missing one) is selected
            val loaded = initVulkanDriver(
                useFreedreno, applicationInfo.nativeLibraryDir, codeCacheDir.absolutePath,
                custom?.first?.absolutePath ?: "", custom?.second ?: "libvulkan_freedreno.so", adreno,
            )
            Log.i(TAG, "Custom Vulkan driver loaded: $loaded ($family GPU)")
        }.onFailure {
            Log.e(TAG, "initVulkanDriver: ", it)
        }

        // UQM reads its content directory as soon as the native thread starts
        if (UIStateManager.tempCodeGroup == "UQM") {
            val uqmDir = OpenMWPaths.USER_FILE_STORAGE + "/uqm"
            mapOf(
                "UQM_CONTENT_DIR" to org.openmw.utils.uqmContentRoot().absolutePath,
                "UQM_CONFIG_DIR" to uqmDir,
                "UQM_SAVE_DIR" to "$uqmDir/save",
                "UQM_MELEE_DIR" to "$uqmDir/teams",
                "UQM_SCR_SHOT_DIR" to "$uqmDir/screenshots",
            ).forEach { (key, value) -> Os.setenv(key, value, true) }
            org.openmw.utils.ensureUqmContent(this)
            UIStateManager.uqmInfoProvider = { org.openmw.utils.uqmInfoText() }
        }
        // The engine libraries load inside super.onCreate and read some variables as they load (OSG_NOTIFY_LEVEL is read
        // by the first log call of a static initialiser), so the environment has to be in place before it
        setEnvironmentVariables()
        super.onCreate(savedInstanceState)

        launchedActivity = true
        val controllerConnected = Engine.isControllerConnected()

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
            if (UIStateManager.useUpstreamEngine) {
                UpstreamEngine.prepare(this)
                UpstreamEngine.exportCachePaths(this)
                getPathToJni(UpstreamEngine.globalPath(this), OpenMWPaths.USER_FILE_STORAGE)
            } else {
                getPathToJni(filesDir.parent!!, OpenMWPaths.USER_FILE_STORAGE)
            }
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

                        val isUIHiddenFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.UI_HIDDEN_STATE_KEY, "false").map { it.toBoolean() } }
                        val isUIHidden by isUIHiddenFlow.collectAsState(initial = false)
                        val autoMouseMode by GameFilesPreferences.getPreference(GameFilesPreferences.AUTO_MOUSE_MODE_KEY, "Hybrid").collectAsState(initial = "Hybrid")
                        val virtualKeyboard by GameFilesPreferences.getPreference(GameFilesPreferences.USE_VIRTUAL_KB, true).collectAsState(initial = true)
                        val isVibrationOnFlow = remember { GameFilesPreferences.getPreference(GameFilesPreferences.VIBRATION_STATE_KEY, "true").map { it.toBoolean() } }
                        val isVibrationOn by isVibrationOnFlow.collectAsState(initial = true)

                        BackHandler {
                            // disable back exit
                            Log.d(TAG, "onGlobalLayout: Back pressed")
                        }

                        // UQM shows the pointer while its ImGui menu is up (libuqm answers isMouseShown)
                        if (UIStateManager.tempCodeGroup == "UQM") {
                            AutoMouseModeComposable()
                        }

                        if (UIStateManager.tempCodeGroup == "OpenMW") {
                            StartupStatus(startedAt = engineStartedAt)
                            AutoMouseModeComposable()
                            //OpenMWDebugStatsOverlay(this@EngineActivity)
                            
                            if (UIStateManager.isPerformanceHudEnabled) FrameTimeWatcher(this@EngineActivity)
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
                            visible = isCursorVisible == 1 && (UIStateManager.tempCodeGroup == "OpenMW" || UIStateManager.tempCodeGroup == "UQM") && !isUIHidden,
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
                            visible = (enableRightThumb && UIStateManager.tempCodeGroup == "OpenMW") ||
                                (UIStateManager.tempCodeGroup == "UQM" && isCursorVisible == 1 && !isUIHidden),
                            enter = fadeIn() + expandIn(),
                            exit = fadeOut() + shrinkOut()
                        ) {
                            ResizableDraggableRightThumbstick(
                                context = this@EngineActivity,
                                containerWidth = containerWidth,
                                containerHeight = containerHeight
                            )
                        }

                        ResizableDraggableThumbstick(
                            context = this@EngineActivity,
                            containerWidth = containerWidth,
                            containerHeight = containerHeight
                        )

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

                    }

                // Remove the Global Layout Listener to prevent multiple calls
                sdlContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun setEnvironmentVariables() {
        fun setEnv(key: String, value: String) {
            try {
                Os.setenv(key, value, true)
                setNativeEnv(key, value)
            } catch (e: Exception) {
                Log.e("Alpha3", "Failed setting env: $key=$value ($e)")
            }
        }

        try {
            // The launcher's own variables, then the ones switched on in the Launch options dialog
            runBlocking { LaunchEnvironment.effective() }.forEach { (key, value) ->
                if (key.isNotEmpty() && value.isNotEmpty()) setEnv(key, value)
            }
            // Launcher switch: plain buffer swaps instead of Swappy frame pacing (read by the engine's SDL window)
            val noSwappy = runBlocking { dataStore.data.first()[GameFilesPreferences.DISABLE_SWAPPY_KEY] != false } // (off unless turned on)
            if (noSwappy) setEnv("OPENMW_NO_SWAPPY", "1") else Os.unsetenv("OPENMW_NO_SWAPPY")
            // Launcher switch: an extra generated frame between the rendered ones (read by the engine's SDL window)
            val frameGeneration = runBlocking { dataStore.data.first()[GameFilesPreferences.FRAME_GENERATION_KEY] == true }
            if (frameGeneration) setEnv("OPENMW_FRAMEGEN", "1") else Os.unsetenv("OPENMW_FRAMEGEN")
            // Launcher choice: frames shown per rendered one (2x to 4x), as the generated frames between two rendered ones
            val multiplier = runBlocking { dataStore.data.first()[GameFilesPreferences.FRAME_GENERATION_MULTIPLIER_KEY] ?: 2 }
            if (frameGeneration) setEnv("OPENMW_FRAMEGEN_FRAMES", (multiplier.coerceIn(2, 4) - 1).toString()) else Os.unsetenv("OPENMW_FRAMEGEN_FRAMES")
            // Launcher choice: the stock engine's OpenGL renderer (read by its support library before the window is made).
            // It wins over [Video] renderer in settings.cfg; OSG_USE_ZINK or OSG_USE_ANGLE in the Launch options still win over it.
            if (UIStateManager.useUpstreamEngine && Os.getenv("OSG_USE_ZINK") == null && Os.getenv("OSG_USE_ANGLE") == null) {
                when (runBlocking { dataStore.data.first()[GameFilesPreferences.GL_RENDERER_KEY] }) {
                    "zink" -> {
                        setEnv("OSG_USE_ZINK", "1")
                        // Launcher slider: the share of each paced interval a Zink frame may take
                        val budget = runBlocking { dataStore.data.first()[GameFilesPreferences.ZINK_FRAME_BUDGET_KEY] ?: 80 }
                        setEnv("OPENMW_ZINK_FRAME_BUDGET", budget.coerceIn(50, 100).toString())
                    }
                    "angle" -> {
                        setEnv("OSG_USE_ANGLE", "1")
                        setEnv("OPENMW_GLES_VERSION", "32")
                    }
                    else -> setEnv("OPENMW_GLES_VERSION", "32")
                }
            }
        } catch (e: ErrnoException) {
            Log.e("Alpha3", "Failed setting environment variables.")
            e.printStackTrace()
        }

        Log.d("EngineActivity", "Environment variables set dynamically from DataStore")
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

        @JvmStatic
        @Suppress("KotlinJniMissingFunction", "LocalVariableName")
        external fun getPathToJni(path_global: String, path_user: String)
    }

    /** Hands the engine's recent frame intervals to the performance overlay, only while that is shown. */
    @Composable
    fun FrameTimeWatcher(activity: EngineActivity) {
        LaunchedEffect(Unit) {
            while (true) {
                runCatching { UIStateManager.frameTimes.value = activity.omwGetFrameTimes().toList() }
                delay(500.milliseconds)
            }
        }
    }

    @Composable
    fun DamageWatcher(activity: EngineActivity, onDamageTaken: (amount: Float) -> Unit) {
        var lastHealth by remember { mutableFloatStateOf(-1f) }

        LaunchedEffect(Unit) {
            while (true) {
                // not once per frame: that would wake the UI thread at the display rate for as long as the game runs
                run {
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
                delay(100.milliseconds)
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
        // These are OpenMW's options; UQM's own option parser exits on the first one it does not know
        // Its console output has nowhere to go on Android, so it is sent to a file next to its saves
        if (UIStateManager.tempCodeGroup == "UQM") {
            val uqmDir = OpenMWPaths.USER_FILE_STORAGE + "/uqm"
            // The core content is in app storage (uqmContentRoot); the add-on packs are downloaded to the shared folder
            return arrayOf("--logfile=$uqmDir/uqm.log", "--addondir=$uqmDir/content/addons")
        }
        return try {
            // Chosen in the Launch options dialog; the values stay whole, a path may have spaces
            val chosen = runBlocking { LaunchArguments.effective() }
            // Dev hook: adb shell am start -n <pkg>/org.openmw.EngineActivity --es omw_args "--load-savegame <path>"
            val intentArgs = tokenizeArguments(intent?.getStringExtra("omw_args").orEmpty())
            val resources = if (UIStateManager.useUpstreamEngine) UpstreamEngine.resourcesDir else OpenMWPaths.USER_RESOURCES
            // The default profile is the engine's own user config; another one is a config folder of its own
            val profile = if (Profiles.isDefault) emptyList() else Profiles.engineArguments()
            val args = listOf("--resources", resources) + profile + chosen + intentArgs
            Log.i("EngineActivity", "OpenMW arguments: $args")
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

fun runNavMesh(context: Context) {
    try {
        org.openmw.utils.NavMeshService.start(context)
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
