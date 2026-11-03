package org.alpha3.launcher.ui.page.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.alpha3.launcher.mods.OmwfxShaders
import org.alpha3.launcher.mods.RafaelShaderPack
import org.alpha3.launcher.mods.ShaderPack
import org.alpha3.launcher.mods.ShaderStep
import org.alpha3.launcher.mods.WetworldPatch

/** An install outlives the settings page: it goes on in the background, and the page shows it again when opened. */
private class ShaderPackState(val pack: ShaderPack) {
    var running by mutableStateOf(false)
    var step by mutableStateOf<ShaderStep?>(null)
    var message by mutableStateOf<String?>(null)
    var installed by mutableStateOf(pack.isInstalled())

    fun start() {
        running = true
        step = null
        message = null
        scope.launch {
            val result = runCatching { pack.install { step = it } }
            message = if (result.isSuccess)
                "Installed in ${pack.folder} and added to openmw.cfg. ${pack.hint}"
            else
                "Failed: ${result.exceptionOrNull()?.message}"
            installed = pack.isInstalled()
            running = false
        }
    }

    companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val omwfx = ShaderPackState(OmwfxShaders)
        val rafael = ShaderPackState(RafaelShaderPack)
    }
}

@Composable
fun ShaderPackRow() = ShaderPackRow(ShaderPackState.omwfx)

@Composable
fun RafaelShaderPackRow() = ShaderPackRow(ShaderPackState.rafael)

@Composable
private fun ShaderPackRow(state: ShaderPackState) {
    Column(Modifier.fillMaxWidth()) {
        SettingRow(title = state.pack.title, subtitle = state.pack.subtitle) {
            Button(onClick = { state.start() }, enabled = !state.running) {
                Text(if (state.installed) "Update" else "Install")
            }
        }
        if (state.running) {
            val step = state.step
            if (step is ShaderStep.Downloading && step.total > 0) {
                LinearProgressIndicator(progress = { step.bytes.toFloat() / step.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val text = when (step) {
                null -> "Starting"
                is ShaderStep.Waiting -> step.text
                is ShaderStep.Downloading -> "Downloading: ${step.bytes / 1024} KB" +
                    if (step.total > 0) " of ${step.total / 1024} KB" else ""
                is ShaderStep.Unpacking -> "Unpacking: ${step.file}"
            }
            Text(text, color = White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        state.message?.let { Text(it, color = White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** The wetworld patch's button: shown once Rafael's Shader Pack (with its wetworld) is installed. */
private object WetworldPatchState {
    var running by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var patched by mutableStateOf(false)
    var present by mutableStateOf(false)

    fun refresh() {
        val file = WetworldPatch.find(RafaelShaderPack)
        present = file != null
        patched = file != null && WetworldPatch.isPatched(file)
    }

    fun run(undo: Boolean) {
        val file = WetworldPatch.find(RafaelShaderPack) ?: return
        running = true
        message = null
        ShaderPackState.scope.launch {
            val result = runCatching { if (undo) WetworldPatch.restore(file) else WetworldPatch.patch(file) }
            message = when {
                result.isFailure -> "Failed: ${result.exceptionOrNull()?.message}"
                undo -> "wetworld is back as it came."
                else -> "Patched. The original is kept as wetworld.omwfx.original; updating the pack undoes the patch."
            }
            refresh()
            running = false
        }
    }
}

@Composable
fun WetworldPatchRow() {
    val state = WetworldPatchState
    // again each time the page is shown (the pack may have been installed or updated since)
    androidx.compose.runtime.LaunchedEffect(ShaderPackState.rafael.installed, ShaderPackState.rafael.running) {
        state.refresh()
    }
    if (!state.present) return
    Column(Modifier.fillMaxWidth()) {
        SettingRow(
            title = if (state.patched) "Faster wetworld reflections (patched)" else "Faster wetworld reflections",
            subtitle = "Patches Rafael's wetworld shader so its reflections are worked out at half size: about a third " +
                "less GPU time in the rain, the reflections a little softer. The original is kept and can be put back.",
        ) {
            Button(onClick = { state.run(undo = state.patched) }, enabled = !state.running && !ShaderPackState.rafael.running) {
                Text(if (state.patched) "Restore original" else "Patch")
            }
        }
        if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}
