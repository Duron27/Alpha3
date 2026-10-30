package org.alpha3.launcher.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import coil3.PlatformContext as CoilPlatformContext
import org.alpha3.launcher.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.tools.navmesh.KotlinNavMesh
import org.alpha3.launcher.ui.controls.UIStateManager

/** Android's Context on Android, a stand-in object elsewhere. */
typealias PlatformContext = CoilPlatformContext

enum class FileBrowserMode {
    FILE, FOLDER
}

/**
 * Things the shared screens need from the host platform but that cannot live in common code
 * (launching the engine, process/service helpers, the Android-only tool screens, ...).
 * The default implementation does nothing, so a platform only overrides what it supports.
 */
open class PlatformFeatures {
    open fun toast(msg: String, isLong: Boolean = false) { println(msg) }
    open fun startGame(isFinish: Boolean = true) {}
    open fun hasInternetConnection(): Boolean = true

    /**
     * Whether the thumbsticks can drive the engine as a virtual gamepad (SDL joystick injection). Without it
     * they fall back to WASD keys and relative mouse motion.
     */
    open val supportsVirtualGamepad: Boolean get() = true

    /** Closes the screen hosting the UI (Activity.finish() on Android). */
    open fun finishHost() {}

    /** Forces the system soft keyboard open for the engine surface. */
    open fun showSoftKeyboard() {}

    /** Reopens the launcher screen (used after leaving the in-game controls editor). */
    open fun restartLauncher() {}

    /** True while the mod downloader is busy; read from composition (Compose state). */
    val isProcessingMods: Boolean get() = org.alpha3.launcher.mods.downloader.StatusInfo.processing

    // Asset / config management
    open fun prepareResources() {}
    open fun resetUserResources() {}
    open fun resetUserConfig() {}
    open fun resetUI() {}

    // Background helpers
    open fun runNavMesh() {}

    // Which tool tabs of the mod screen the platform provides; the others are not shown at all
    val supportsModDownloader: Boolean get() = modDownloaderHost != null

    /** The browser, background work and links the mod downloader needs; null where there is no mod downloader. */
    open val modDownloaderHost: org.alpha3.launcher.mods.downloader.ModDownloaderHost? get() = null
    val supportsModTools: Boolean get() = toolFs != null && toolPlatform != null
    open val supportsTerminal: Boolean get() = false
    val supportsKram: Boolean get() = kramRunner != null

    // Tool screens that are only available where the platform provides them
    @Composable open fun Terminal() {}
    @Composable open fun UqmDownloads() {}
    @Composable fun ModDownloader() { if (supportsModDownloader) org.alpha3.launcher.mods.downloader.ModDownloaderScreen() }
    /** Storage and host description for the Kotlin OpenMW tools (clean, leveled lists, groundcover, light fixes); null where unavailable. */
    open val toolFs: org.alpha3.launcher.tools.openmw.ToolFs? get() = null
    open val toolPlatform: org.alpha3.launcher.tools.openmw.ToolPlatform? get() = null

    /** Lists and switches the launcher plugins; null where plugins are not supported. */
    open val plugins: PluginManager? get() = null

    /** Installs and lists downloadable GPU drivers; null where custom drivers are not supported. */
    open val gpuDrivers: GpuDriverManager? get() = null

    /**
     * Where there are no custom GPU drivers: the stock engine's renderers to choose from, as the
     * [GameFilesPreferences.GL_RENDERER_KEY] value and its name, the default first. Empty: no choice.
     */
    open val glRenderers: Map<String, String> get() = emptyMap()

    /** Launches the native kram binary for texture conversion; null where it is unavailable. */
    open val kramRunner: org.alpha3.launcher.tools.KramTool.Runner? get() = null

    /** Converts the mod list's textures for the stock OpenMW engine ahead of the game; null where unavailable. */
    open val texturePrepass: TexturePrepassRunner? get() = null

    @Composable
    open fun FileBrowserPopup(
        initialDirectory: String,
        mode: FileBrowserMode,
        onDismiss: () -> Unit,
        onFileSelected: ((String) -> Unit)?,
        onFolderSelected: ((String) -> Unit)?
    ) {}


    @Composable
    open fun CustomIconPickerButton(
        buttonId: Int,
        buttonUri: MutableState<Uri?>,
        containerWidth: Float,
        containerHeight: Float
    ) {}

    @Composable
    open fun InitialDirectorySelection(onSelect: (String) -> Unit, onDismiss: () -> Unit) {}
}

object Platform {
    var features: PlatformFeatures = PlatformFeatures()
}

// ---- Call-site friendly wrappers; these keep the names the screens already use ----

fun MToast(msg: String, isLong: Boolean = false) = Platform.features.toast(msg, isLong)

@Suppress("UnusedReceiverParameter")
fun PlatformContext.startGame(isFinish: Boolean = true) {
    if (UIStateManager.useNavmesh || UIStateManager.navmeshBusy) {
        MToast("The navmesh is still being generated. Wait for it to finish before starting the game.", true)
        return
    }
    Platform.features.startGame(isFinish)
}

@Suppress("UNUSED_PARAMETER")
fun runNavMesh(context: PlatformContext) = Platform.features.runNavMesh()

/** Generates navmesh.db with the native tool, or with the Kotlin port when that experimental option is on in Mod Tools. */
suspend fun runNavMeshSelected(context: PlatformContext) {
    val kotlin = GameFilesPreferences.getPreference(GameFilesPreferences.KOTLIN_NAVMESH_KEY, false).first()
    val fs = Platform.features.toolFs
    val platform = Platform.features.toolPlatform
    if (!kotlin || fs == null || platform == null) {
        runNavMesh(context)
        return
    }
    try {
        UIStateManager.navmeshBusy = true
        withContext(Dispatchers.Default) {
            KotlinNavMesh.run(fs, platform, OpenMWPaths.USER_OPENMW_CFG, "${OpenMWPaths.USER_DATA}/navmesh.db", "${OpenMWPaths.ACTIVE_CONFIG}/navmeshtool.log")
        }
    } catch (e: Exception) {
        MToast("Kotlin navmesh failed: ${e.message ?: e}", true)
    } finally {
        UIStateManager.useNavmesh = false
        UIStateManager.navmeshBusy = false
    }
}

@Suppress("UNUSED_PARAMETER")
class UserManageAssets(context: PlatformContext) {
    fun resourcePrepare() = Platform.features.prepareResources()
    fun resetUserResources() = Platform.features.resetUserResources()
    fun resetUserConfig() = Platform.features.resetUserConfig()
    fun resetUI() = Platform.features.resetUI()
}

@Composable fun Terminal() = Platform.features.Terminal()
@Composable fun UqmDownloads() = Platform.features.UqmDownloads()
@Composable fun SSOHome() = Platform.features.ModDownloader()
@Composable fun KramConversion(context: PlatformContext) =
    org.alpha3.launcher.ui.page.mod.KramConversionScreen(context, Platform.features.kramRunner)

@Composable
fun FileBrowserPopup(
    initialDirectory: File = File(OpenMWPaths.USER_FILE_STORAGE),
    onDismiss: () -> Unit,
    onFileSelected: ((File) -> Unit)? = null,
    onFolderSelected: ((File) -> Unit)? = null,
    mode: FileBrowserMode = FileBrowserMode.FILE
) = Platform.features.FileBrowserPopup(
    initialDirectory = initialDirectory.path,
    mode = mode,
    onDismiss = onDismiss,
    onFileSelected = onFileSelected?.let { cb -> { path: String -> cb(File(path)) } },
    onFolderSelected = onFolderSelected?.let { cb -> { path: String -> cb(File(path)) } }
)

@Composable
fun InitialDirectorySelection(onSelect: (File) -> Unit, onDismiss: () -> Unit) =
    Platform.features.InitialDirectorySelection({ path -> onSelect(File(path)) }, onDismiss)

@Suppress("UNUSED_PARAMETER")
@Composable
fun CustomIconPickerButton(
    context: PlatformContext,
    buttonId: Int,
    buttonUri: MutableState<Uri?>,
    containerWidth: Float,
    containerHeight: Float
) = Platform.features.CustomIconPickerButton(buttonId, buttonUri, containerWidth, containerHeight)
