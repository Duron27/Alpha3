package org.alpha3.launcher.ui.page.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One answer in the in-app guide. The README's "How to use" section follows the same steps. */
data class HelpTopic(
    val title: String,
    val steps: List<String>,
    /** Extra words people might search for that the text itself doesn't use. */
    val keywords: String = "",
)

val helpTopics: List<HelpTopic> = listOf(
    HelpTopic(
        "Getting started",
        listOf(
            "Install Morrowind (Steam, GOG or disc) on your device or copy its \"Data Files\" folder over.",
            "On the Data tab, add the Morrowind \"Data Files\" folder with the folder button at the bottom. It becomes load order 1.",
            "On the Content tab, tick Morrowind.esm, Tribunal.esm and Bloodmoon.esm (whichever you own).",
            "Press the Play button at the bottom right.",
        ),
        "install setup first run begin start morrowind data files game files",
    ),
    HelpTopic(
        "Download a whole mod list (Mod Downloader)",
        listOf(
            "Open the Mods group and choose the Mod Downloader tab. The first time, it fetches the mod lists, which can take a moment.",
            "Tap the list name at the top (for example i-heart-vanilla) and pick a list. The number is how many mods it has.",
            "Tap the gear for Download Options. Leave \"Allow Archive Extraction\" on so downloaded archives are unpacked, and leave \"Sync, Download, and Extract\" selected to do the whole job.",
            "Enter your Nexus Mods API key in Download Options and save it. Premium accounts download automatically. Free accounts confirm each file on the Nexus website: see \"Free Nexus account downloads\".",
            "Tap the download button at the top right. Each mod shows its progress, speed and extraction status. The hourly and daily Nexus limits are shown above the list.",
            "When it finishes, the list is installed into its own profile folder (Alpha3/config/<list>): settings.cfg is copied from the default profile and the list's cfg replaces openmw.cfg there. A box shows what was done.",
            "Missing data folders and plugins are commented out (a ; is added) so the game still starts. Choose Switch to use the new profile, or Stay on current. Review Logs shows what happened.",
            "The menu at the top right has Profile: <name>, where you switch, create or delete profiles. Default is the plain Alpha3/config folder.",
        ),
        "nexus api key premium free download modlist list umo i-heart-vanilla total-overhaul extract archive sync apply config rate limit",
    ),
    HelpTopic(
        "Free Nexus account downloads",
        listOf(
            "Sign in to Nexus Mods in your phone's browser, and save the API key of the SAME account in Download Options.",
            "Tap the download button. For each file that is not downloaded yet, its Nexus page opens in a browser tab, one at a time. The biggest files are queued first.",
            "Scroll down to the Free: Slow download box and press \"Slow download\" (it is greyed out for a few seconds). Do not press the blue \"Fast download - free trial\" button.",
            "The launcher comes back to the front, starts the download and opens the next file's page. Repeat for each file; you do not have to wait for a download to finish.",
            "Files go to Alpha3/OpenMW/CACHE and are unpacked into Alpha3/OpenMW/Mods. The top of the list shows how many files are left, downloading, extracting and waiting for the browser. Big files can take several minutes on a free account.",
            "A download shows its percentage and speed with a Pause button (it becomes Resume). \"Skip this file\" at the top moves on if you closed a Nexus tab without pressing Slow download.",
            "Files are saved as .part in the CACHE folder and only used when complete. A dropped connection is retried up to four times. If you close the app, press Download on the file again: it says \"Partial download\" and continues where it stopped after you press Slow download once more.",
            "\"Processing Complete\" appears once every file is downloaded and unpacked.",
            "If the browser and the API key are different Nexus accounts, or the link expired, the launcher says so: press Slow download again with the right account.",
        ),
        "nexus free slow download browser tab queue nxm manual key expired account api key sign in",
    ),
    HelpTopic(
        "Download options",
        listOf(
            "Allow Archive Extraction: turn off to download only.",
            "Sync, Download, and Extract / Only do the sync process: the second only refreshes the list data without downloading mods.",
            "Sync threads: how many mods are looked up at the same time during the sync step. Lower it if requests fail or Nexus limits you.",
            "Wipe database: clears the mod entries the app has stored, so the lists are synced again. It does not touch files already on disk.",
            "Remove API Key: signs the Nexus key out of the app.",
        ),
        "threads wipe database api key only sync download only extraction",
    ),
    HelpTopic(
        "Add a mod folder by hand",
        listOf(
            "Go to the Data tab and tap the folder button at the bottom.",
            "Pick the mod's folder (the one with Meshes, Textures and so on inside it).",
            "Drag the handle at the right of a row to move it; its Load Order number shows its position. Later rows win when two mods have the same file.",
            "The tick on the left shows the folder is enabled.",
        ),
        "data folder load order reorder drag priority mod path manual install",
    ),
    HelpTopic(
        "Turn plugins on and off (Content)",
        listOf(
            "Open the Content tab: it lists every .esm, .esp and .omwaddon found in your data folders.",
            "Tick the plugins you want. Masters (Morrowind.esm and so on) go first.",
            "Plugins at the top load first. Move a plugin to change which one wins.",
        ),
        "content plugins esp esm omwaddon load order enable disable masters",
    ),
    HelpTopic(
        "Groundcover and resources",
        listOf(
            "Groundcover tab: plugins that hold grass and plants that OpenMW draws as groundcover.",
            "Resources tab: archives (BSA) and other resource entries the game loads.",
            "Mod Tools can build the groundcover plugin for you (see Mod Tools below).",
        ),
        "groundcover grass bsa archive resources fallback-archive",
    ),
    HelpTopic(
        "Mod Tools: clean, merge and fix",
        listOf(
            "Open the Mod Tools tab. Tick the tools you want and press Run selected, or press Run next to one tool.",
            "Clean plugins removes junk the Construction Set leaves in plugins. It writes a cleaned copy and keeps the original.",
            "Merge records combines records that several mods change (leveled lists, NPC and creature inventories, spells, AI, containers, armour, dialogue) so every mod's additions work. It writes alpha3_merged.omwaddon.",
            "Groundcover turns grass and plants from your plugins into OpenMW groundcover (alpha3_groundcover.omwaddon).",
            "Light fixes rebalances every light for OpenMW lighting (alpha3_lightfixes.omwaddon).",
            "The generated plugins land in the alpha3_generated folder. Run the tools again after you change your load order.",
            "When a run ends, the config is checked and lines with missing files are commented out.",
        ),
        "tes3cmd clean merge leveled lists npc inventory groundcoverify lightfixes s3lightfixes delta merged tools run",
    ),
    HelpTopic(
        "Free up space: remove overridden files (experimental)",
        listOf(
            "Turn on Settings > Launcher Settings > System & Debug > Enable Developer Options. The option only shows up then.",
            "Open Mod Tools and press Scan. Nothing is deleted yet: it counts mod files that a later mod in the load order replaces.",
            "If you want them gone, press the Delete button and confirm. Deleted files cannot be restored except by downloading the mod again.",
            "If you change the load order afterwards, a deleted file might have been the one you wanted. Plugins, archives and your Morrowind install are never touched.",
        ),
        "delete duplicate duplicates overridden unused space storage prune clean up disk size",
    ),
    HelpTopic(
        "Settings.cfg and Launcher Settings",
        listOf(
            "Open the Settings group at the top left.",
            "Launcher Settings: graphics, GPU driver, interface and system options. Changes marked \"next launch\" apply when you start the game again.",
            "Settings.cfg: the game's own settings file, edited as a list of options.",
            "Controls: set up and place the on-screen controls.",
        ),
        "settings cfg graphics driver turnip freedreno resolution controls touch gamepad overlay renderer",
    ),
    HelpTopic(
        "Add, edit and delete on-screen buttons",
        listOf(
            "Open Settings > Controls and press Configure Controls. The game starts in control-editing mode. (Reset Controls on the same page puts the default layout back.)",
            "Tap the expand arrow to open the menu, then tap the edit icon to enter edit mode. Tap it again to leave edit mode.",
            "Add: in edit mode, tap the red + to add a button. The same + switches on the right thumbstick if it is off.",
            "Move: press and hold a button, then drag it. It snaps to the grid when you let go.",
            "Edit: tap the spinning yellow dots on a button to open its settings. Options, Interact, Colors and Vibration set what it does and how it looks. You can also give it an icon from a URL, make it toggleable, show it in the mouse UI, or apply the style to all buttons.",
            "Resize or delete: in the same settings box use the Increase Size / Delete / Decrease Size row. + makes the button bigger, the bin deletes it and \u2212 makes it smaller.",
            "Scroll to the bottom of the box and press Close, then leave edit mode to keep the layout.",
        ),
        "controls buttons thumbstick joystick touch layout edit mode editmode add delete remove resize move drag customize configure on-screen overlay gamepad",
    ),
    HelpTopic(
        "Change the GPU driver",
        listOf(
            "Open Settings > Launcher Settings > Graphics & Performance.",
            "Turn on \"Use Freedreno driver\" to use an open-source Turnip driver instead of the device's own.",
            "Pick the bundled driver, or download another from the list or from a link to a driver .zip. Only install drivers you trust.",
            "Close and start the game again for the change to apply.",
        ),
        "turnip freedreno vulkan adreno driver zip gpu graphics crash black screen",
    ),
    HelpTopic(
        "Something is wrong",
        listOf(
            "The game won't start or crashes: open the menu (the three lines at the top right) and choose View last crash. Settings > Launcher Settings > System & Debug has the custom log output.",
            "A plugin or folder is greyed out or has a ; in front of it in the config: its file was missing, so it was turned off. Download it again and re-enable it.",
            "Mods that need Morrowind are missing: check the first Data row points at the folder that contains Morrowind.esm.",
            "Downloads stop: check your Nexus limits above the list, the free space shown in the downloader, and your connection.",
        ),
        "crash log error missing file semicolon commented black screen not working troubleshoot help problem fix",
    ),
)

/** The in-app guide: a searchable list of how-tos. */
@Composable
fun HelpScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val gold = MaterialTheme.colorScheme.primary
    val results = remember(query) { searchHelp(query) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search the guide: nexus, load order, driver, crash…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
            },
        )
        if (results.isEmpty()) {
            Text(
                "No answers for \"$query\". Try a shorter word, or one of: download, nexus, config, load order, driver, crash.",
                modifier = Modifier.padding(8.dp),
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.title }) { topic ->
                // A search with few hits shows the answers straight away.
                val open = opened == topic.title || (query.isNotBlank() && results.size <= 2)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .clickable { opened = if (opened == topic.title) null else topic.title }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(topic.title, color = gold, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = gold)
                    }
                    if (open) {
                        topic.steps.forEachIndexed { i, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${i + 1}.", color = gold, fontWeight = FontWeight.Bold)
                                Text(step, color = Color.White, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Topics whose title, keywords or steps contain every word of [query], best matches (title hits) first. */
internal fun searchHelp(query: String, topics: List<HelpTopic> = helpTopics): List<HelpTopic> {
    val words = query.lowercase().split(' ', ',', '.').filter { it.isNotBlank() }
    if (words.isEmpty()) return topics
    return topics
        .filter { t ->
            val haystack = (t.title + " " + t.keywords + " " + t.steps.joinToString(" ")).lowercase()
            words.all { it in haystack }
        }
        .sortedByDescending { t -> words.count { it in t.title.lowercase() } }
}
