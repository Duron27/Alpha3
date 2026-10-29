package org.alpha3.launcher.ui.view

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.Profiles

/**
 * Picks the profile (see [Profiles]) the launcher and the game use: the default `Alpha3/config` folder, or one folder
 * per mod list. Switching reloads the launcher screens, which then read that profile's openmw.cfg and settings.cfg.
 */
@Composable
fun ProfilesDialog(onClose: () -> Unit) {
    // Reading the revision makes the list rebuild after a switch, create or delete
    val revision = Profiles.revision + Profiles.listRevision
    val profiles = remember(revision) { listOf(Profiles.DEFAULT) + Profiles.list() }
    val active = remember(revision) { Profiles.active }
    var newName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = 420.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Text("Profiles", style = MaterialTheme.typography.titleLarge)
            Text(
                "A profile is a config folder with its own openmw.cfg, settings.cfg, saves and navmesh. " +
                    "Applying a mod list's config creates a profile named after the list. The default profile is Alpha3/config.",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(profiles) { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Profiles.switchTo(name) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = name == active, onClick = { Profiles.switchTo(name) })
                        Column(Modifier.weight(1f)) {
                            Text(if (name == Profiles.DEFAULT) "Default" else name)
                            Text(Profiles.folderOf(name), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (name != Profiles.DEFAULT) {
                            TextButton(onClick = { confirmDelete = name }) { Text("Delete") }
                        }
                    }
                }
            }
            confirmDelete?.let { name ->
                Text("Delete profile \"$name\" with its config, saves and navmesh?", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { confirmDelete = null }) { Text("Keep") }
                    TextButton(onClick = { Profiles.delete(name); confirmDelete = null }) { Text("Delete") }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it; message = "" },
                label = { Text("New profile name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (message.isNotEmpty()) Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Close") }
                Button(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val clean = Profiles.sanitize(newName)
                        when {
                            clean.isEmpty() -> message = "Use letters, digits, dot, dash or underscore."
                            File(Profiles.folderOf(clean)).exists() -> message = "\"$clean\" already exists."
                            else -> {
                                // Starts as a copy of the default profile's openmw.cfg and settings.cfg
                                runCatching { Profiles.create(clean); Profiles.switchTo(clean) }
                                    .onFailure { message = it.message ?: "Could not create the profile." }
                                newName = ""
                            }
                        }
                    },
                ) { Text("Create and switch") }
            }
        }
    }
}
