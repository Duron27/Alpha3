package org.alpha3.launcher.ui.page.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.utils.TexturePrepassProgress
import org.alpha3.launcher.utils.TexturePrepassResult

/** The run outlives the settings page: it goes on in the background, and the page shows it again when opened. */
private object TexturePrepassState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var running by mutableStateOf(false)
    var progress by mutableStateOf<TexturePrepassProgress?>(null)
    var message by mutableStateOf<String?>(null)

    fun start() {
        val runner = Platform.features.texturePrepass ?: return
        running = true
        progress = null
        message = null
        scope.launch {
            val result = runCatching { runner.run { progress = it } }
            val last = progress
            message = when (result.getOrNull()) {
                TexturePrepassResult.Done -> "Done: ${last?.converted ?: 0} converted, ${last?.cached ?: 0} already cached" +
                    if ((last?.failed ?: 0) > 0) ", ${last?.failed} failed" else ""
                TexturePrepassResult.Cancelled -> "Stopped. What was converted is kept."
                TexturePrepassResult.ConversionOff -> "Texture conversion is off in settings.cfg ([Textures] enable texture conversion)."
                TexturePrepassResult.Busy -> "Already running."
                null -> "Failed: ${result.exceptionOrNull()?.message}"
            }
            running = false
        }
    }
}

@Composable
fun TexturePrepassRow() {
    val runner = Platform.features.texturePrepass ?: return
    val state = TexturePrepassState
    Column(Modifier.fillMaxWidth()) {
        SettingRow(
            title = "Convert textures now",
            subtitle = "For stock OpenMW: converts the mod list's textures ahead, so the game loads them from its cache."
        ) {
            if (state.running) TextButton(onClick = { runner.cancel() }) { Text("Stop") }
            else Button(onClick = { state.start() }) { Text("Convert") }
        }
        val progress = state.progress
        if (state.running) {
            if (progress == null || progress.total == 0) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
            }
            val text = when {
                progress == null -> "Starting"
                progress.listing -> "Listing data directories: ${progress.done} of ${progress.total}"
                else -> "${progress.done} of ${progress.total} textures: ${progress.converted} converted, ${progress.cached} cached"
            }
            Text(text, color = White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        state.message?.let { Text(it, color = White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}
