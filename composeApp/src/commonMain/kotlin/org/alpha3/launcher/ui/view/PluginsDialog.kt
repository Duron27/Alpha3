package org.alpha3.launcher.ui.view

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.utils.PluginManager

/**
 * Switches the plugins found in the plugin folders on or off. A plugin is code running inside the launcher, so
 * nothing is loaded until it is enabled here, and a change applies the next time the launcher starts.
 */
@Composable
fun PluginsDialog(manager: PluginManager, onClose: () -> Unit) {
    var revision by remember { mutableStateOf(0) }
    val plugins = remember(revision) { manager.list() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = 420.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Text("Plugins", style = MaterialTheme.typography.titleLarge)
            Text(
                "A plugin is a .jar that runs inside the launcher. Only enable ones you trust. " +
                    "Changes apply the next time the launcher starts.",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            manager.folders.forEach {
                Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (plugins.isEmpty()) {
                Text("No plugins found.", modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                items(plugins) { plugin ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plugin.name)
                            val status = when {
                                plugin.error != null -> "Failed to load: ${plugin.error}"
                                plugin.enabled && plugin.loaded -> "Running"
                                plugin.enabled -> "Starts after a restart"
                                plugin.loaded -> "Stops after a restart"
                                else -> "Off"
                            }
                            Text(plugin.file, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                status,
                                fontSize = 12.sp,
                                color = if (plugin.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = plugin.enabled, onCheckedChange = { manager.setEnabled(plugin.file, it); revision++ })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
