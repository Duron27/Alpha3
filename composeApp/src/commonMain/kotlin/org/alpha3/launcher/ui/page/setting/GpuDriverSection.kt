package org.alpha3.launcher.ui.page.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.GpuDriverPackage
import org.alpha3.launcher.utils.GpuDrivers
import org.alpha3.launcher.utils.GpuFamily
import org.alpha3.launcher.utils.InstalledGpuDriver
import org.alpha3.launcher.utils.MToast
import org.alpha3.launcher.utils.Platform

/**
 * Installs and selections run here rather than in the settings page's scope, so leaving the page while a driver
 * downloads does not cancel the step that selects it.
 */
private val driverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Pick the Vulkan driver, and download more of them. Shown only where the platform can load custom drivers.
 * The choice takes effect on the next launch of the game.
 */
@Composable
fun GpuDriverSection() {
    val manager = Platform.features.gpuDrivers ?: return
    val family = manager.gpuFamily
    // the bundled Turnip runs on Adreno only; on Mali that choice is the device's own driver
    val mali = family == GpuFamily.MALI
    val builtinName = if (mali) "Mali system driver" else "Bundled Turnip"
    val scope = rememberCoroutineScope()
    val selected by GameFilesPreferences.getPreference(GameFilesPreferences.SELECTED_GPU_DRIVER, GpuDrivers.BUILTIN_ID)
        .collectAsState(initial = GpuDrivers.BUILTIN_ID)

    var installed by remember { mutableStateOf<List<InstalledGpuDriver>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var busyUrl by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(-1f) }
    var customUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        installed = manager.installed()
        loaded = true
    }

    fun select(id: String) {
        driverScope.launch { GameFilesPreferences.setPreference(GameFilesPreferences.SELECTED_GPU_DRIVER, id) }
    }

    fun install(url: String) {
        if (busyUrl != null) return
        busyUrl = url
        progress = -1f
        error = null
        driverScope.launch {
            try {
                val driver = manager.install(url) { progress = it }
                GameFilesPreferences.setPreference(GameFilesPreferences.SELECTED_GPU_DRIVER, driver.id)
                reload++
                customUrl = ""
                MToast("${driver.name} installed. It is used the next time the game starts.")
            } catch (e: Exception) {
                error = e.message ?: "The driver could not be installed"
            } finally {
                busyUrl = null
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("GPU driver", style = MaterialTheme.typography.titleSmall)
        Text(
            if (mali) "PanVK is experimental: if the game will not start with it, go back to the Mali system driver. " +
                "Takes effect on the next launch."
            else "Used when the Freedreno driver is on. Takes effect on the next launch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val selectedMissing = selected != GpuDrivers.BUILTIN_ID && installed.none { it.id == selected } && loaded
        DriverRow(builtinName, if (mali) "The phone's own driver" else "Comes with the app", selected == GpuDrivers.BUILTIN_ID || selectedMissing, onSelect = { select(GpuDrivers.BUILTIN_ID) })
        if (selectedMissing) {
            Text(
                "The driver chosen earlier is no longer installed, the ${if (mali) "system" else "bundled"} one is used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // a driver for the other GPU family is listed (so it can be removed) but cannot be picked
        installed.forEach { driver ->
            val fits = driver.family == GpuFamily.UNKNOWN || family == GpuFamily.UNKNOWN || driver.family == family
            DriverRow(
                title = driver.name,
                subtitle = if (fits) listOf(driver.version, driver.author).filter { it.isNotEmpty() }.joinToString(" · ")
                    else "For ${driver.family.label} GPUs, not this device",
                selected = selected == driver.id,
                onSelect = { if (fits) select(driver.id) },
                onDelete = {
                    scope.launch {
                        // a deleted driver that was selected goes back to the bundled one
                        if (selected == driver.id) select(GpuDrivers.BUILTIN_ID)
                        manager.remove(driver.id)
                        reload++
                    }
                },
            )
        }

        Text("Download a driver", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        GpuDrivers.catalog.filter { family == GpuFamily.UNKNOWN || it.family == family }.forEach { pkg ->
            val isInstalled = installed.any { it.sourceUrl == pkg.url }
            PackageRow(pkg, isInstalled, busy = busyUrl == pkg.url, anyBusy = busyUrl != null, progress = progress) { install(pkg.url) }
        }

        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it.trim() },
            label = { Text("Or a link to a driver .zip") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { install(customUrl) }, enabled = busyUrl == null && customUrl.startsWith("https://")) {
                Text("Download")
            }
            if (busyUrl == customUrl && customUrl.isNotEmpty()) ProgressBar(progress, Modifier.padding(start = 12.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Drivers are native code that runs inside the game. Only install ones you trust.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DriverRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
        }
    }
}

@Composable
private fun PackageRow(pkg: GpuDriverPackage, installed: Boolean, busy: Boolean, anyBusy: Boolean, progress: Float, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.bodyMedium)
            Text(pkg.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) ProgressBar(progress, Modifier.padding(top = 4.dp))
        }
        TextButton(onClick = onDownload, enabled = !anyBusy) { Text(if (installed) "Installed · download again" else "Download") }
    }
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    if (progress in 0f..1f) LinearProgressIndicator(progress = { progress }, modifier = modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier = modifier.fillMaxWidth())
}
