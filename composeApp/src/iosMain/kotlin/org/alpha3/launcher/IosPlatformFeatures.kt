package org.alpha3.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.engine.EngineBridge
import org.alpha3.launcher.files.FileBrowser
import org.alpha3.launcher.files.copyPlatformFileToDirectory
import org.alpha3.launcher.globals.PlayBridge
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.utils.FileBrowserMode
import org.alpha3.launcher.utils.PlatformFeatures
import org.alpha3.launcher.utils.Uri
import org.alpha3.launcher.utils.toUri

/** iOS implementation of the shared [PlatformFeatures]. */
class IosPlatformFeatures : PlatformFeatures() {

    override fun showSoftKeyboard() = SystemKeyboard.toggle()

    override val glRenderers = linkedMapOf(
        "gles" to "OpenGL ES 3.0 (Apple)",
        "angle" to "ANGLE (OpenGL ES 3.0 on Metal)",
    )

    override val modDownloaderHost get() = org.alpha3.launcher.mods.downloader.IosModDownloaderHost

    override val texturePrepass: org.alpha3.launcher.utils.TexturePrepassRunner get() = IosTexturePrepass

    override val toolFs get() = IosToolFs
    override val toolPlatform get() = iosToolPlatform()

    override fun toast(msg: String, isLong: Boolean) {
        println("TOAST: $msg")
        PlayBridge.addLog(msg)
    }

    // Swift owns the engine process; it provides the play callback through MainViewController.
    override fun startGame(isFinish: Boolean) {
        PlayBridge.onPlay?.invoke()
    }

    override fun prepareResources() = OpenMWPaths.setupResources()

    override fun resetUI() {
        // Button layout files live below userUI; clearing them restores the defaults.
        File(UIStateManager.userUI).deleteRecursively()
    }

    override fun resetUserResources() {
        PlayBridge.onResetSettings?.invoke()
    }

    override fun resetUserConfig() {
        PlayBridge.onResetSettings?.invoke()
    }

    @Composable
    override fun FileBrowserPopup(
        initialDirectory: String,
        mode: FileBrowserMode,
        onDismiss: () -> Unit,
        onFileSelected: ((String) -> Unit)?,
        onFolderSelected: ((String) -> Unit)?
    ) {
        if (mode == FileBrowserMode.FOLDER) {
            // iOS cannot browse outside the sandbox; the system picker returns a security scoped folder
            // which is copied into the app container.
            val scope = rememberCoroutineScope()
            val launcher = rememberDirectoryPickerLauncher { directory ->
                if (directory == null) {
                    onDismiss()
                } else scope.launch {
                    val destination = OpenMWPaths.SECOND_USER_FILE_STORAGE + "/" + directory.name
                    if (!copyPlatformFileToDirectory(directory, destination)) {
                        println("FileBrowserPopup: copy of ${directory.name} was incomplete")
                    }
                    // Validate whatever arrived; the game folder check reports a missing Morrowind.ini.
                    onFolderSelected?.invoke(destination)
                    onDismiss()
                }
            }
            LaunchedEffect(Unit) { launcher.launch() }
        } else {
            Dialog(onDismissRequest = onDismiss) {
                Surface(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
                    shape = androidx.compose.material3.MaterialTheme.shapes.medium
                ) {
                    Box {
                        FileBrowser(startPath = initialDirectory)
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) { Text("Close") }
                    }
                }
            }
        }
    }

    @Composable
    override fun InitialDirectorySelection(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
        FileBrowserPopup(
            initialDirectory = OpenMWPaths.SECOND_USER_FILE_STORAGE,
            mode = FileBrowserMode.FOLDER,
            onDismiss = onDismiss,
            onFileSelected = null,
            onFolderSelected = onSelect
        )
    }

    @Composable
    override fun CustomIconPickerButton(
        buttonId: Int,
        buttonUri: MutableState<Uri?>,
        containerWidth: Float,
        containerHeight: Float
    ) {
        val scope = rememberCoroutineScope()
        val picker = rememberFilePickerLauncher(type = FileKitType.Image) { picked ->
            if (picked != null) scope.launch {
                val destinationDir = UIStateManager.userUI
                if (copyPlatformFileToDirectory(picked, destinationDir)) {
                    val saved = "$destinationDir/${picked.name}".toUri()
                    UIStateManager.saveImageUri(buttonId, saved)
                    buttonUri.value = saved
                    UIStateManager.saveButtonState(containerWidth, containerHeight)
                }
            }
        }
        val hasIcon = buttonUri.value != null
        Button(onClick = {
            if (hasIcon) {
                buttonUri.value?.let { File(it.path).delete() }
                buttonUri.value = null
                UIStateManager.buttonStates.value[buttonId]?.let {
                    UIStateManager.updateButtonState(buttonId, it.copy(uri = null))
                }
                UIStateManager.saveButtonState(containerWidth, containerHeight)
            } else {
                picker.launch()
            }
        }) {
            Text(if (hasIcon) "Remove Icon" else "Choose Icon (png, jpg, gif)")
        }
    }
}

/**
 * Called from Swift to give the shared on-screen controls access to the SDL input entry points:
 * `Platform_iosKt.installEngineBridge(bridge: ...)`.
 */
fun installEngineBridge(bridge: EngineBridge) {
    Engine.bridge = bridge
}
