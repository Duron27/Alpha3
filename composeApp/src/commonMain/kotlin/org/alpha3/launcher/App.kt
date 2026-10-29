@file:OptIn(InternalCoroutinesApi::class)

package org.alpha3.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import org.alpha3.launcher.globals.PlayBridge
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import org.alpha3.launcher.files.*
import org.alpha3.launcher.mods.ModValuesList
import org.alpha3.launcher.mods.readModValues
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.ui.navigation.RootNav
import org.alpha3.launcher.ui.theme.OpenMWTheme
import org.alpha3.launcher.ui.view.MoeDialog
import org.alpha3.launcher.utils.BouncingBackground
import org.alpha3.launcher.utils.ReadAndDisplayIniValues
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.launch

/**
 * The launcher screen shared with Android: same pages, theme and in-game controls (see ui/MainScreen.kt).
 * Platform specific behaviour comes from [org.alpha3.launcher.utils.Platform.features].
 */
@OptIn(DelicateCoroutinesApi::class)
@ExperimentalFoundationApi
@Composable
@Preview
fun App() {
    LaunchedEffect(Unit) {
        OpenMWPaths.setupResources()
        println("Documents: ${OpenMWPaths.SECOND_USER_FILE_STORAGE}")
        println("Config: ${OpenMWPaths.USER_CONFIG}")
        println("Resources Path: ${OpenMWPaths.USER_RESOURCES}")
    }

    OpenMWTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize()) {
            RootNav()
            MoeDialog()
        }
    }
}

/** The original iOS only launcher screen, kept until the shared screens are verified on device. */
@OptIn(DelicateCoroutinesApi::class)
@ExperimentalFoundationApi
@Composable
fun LegacyApp() {
    println("APP COMPOSABLE START")
    LaunchedEffect(Unit) {
        OpenMWPaths.setupResources()
        println("LAUNCHED EFFECT START")
        println("=========================================================================")
        println("IOS SIMULATOR APP PATHS:")
        println("Documents: ${OpenMWPaths.SECOND_USER_FILE_STORAGE}")
        println("Config: ${OpenMWPaths.USER_CONFIG}")
        println("To open in Finder run:")
        println("open ${OpenMWPaths.SECOND_USER_FILE_STORAGE}")
        println("Resources Path: ${OpenMWPaths.USER_RESOURCES}")
        println("=========================================================================")
        //patchShadersLinking()
        //patchShadersToGLES()
    }

    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        var showLogs by remember { mutableStateOf(false) }
        val modValues = remember { readModValues() }
        val scope = rememberCoroutineScope()
        
        var isImporting by remember { mutableStateOf(false) }

        val launcher = rememberDirectoryPickerLauncher { directory ->
            directory?.let { dir ->
                scope.launch {
                    isImporting = true
                    val dest = OpenMWPaths.SECOND_USER_FILE_STORAGE + "/Data Files"
                    val success = copyPlatformFileToDirectory(dir, dest)
                    println("Import result: $success to $dest")
                    isImporting = false
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            BouncingBackground()

            // Main Content Area
            Column(
                modifier = Modifier
                    .safeContentPadding()
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!showContent) {
                    // Dashboard View
                    ModValuesList(modValues)
                } else {
                    // Settings View
                    var explorerPath by remember { mutableStateOf(OpenMWPaths.USER_FILE_STORAGE) }

                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ReadAndDisplayIniValues()

                        Spacer(modifier = Modifier.height(24.dp))
                        Text("File Explorer", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { explorerPath = OpenMWPaths.USER_FILE_STORAGE },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (explorerPath == OpenMWPaths.USER_FILE_STORAGE) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.1f)
                                )
                            ) { Text("App Support", fontSize = 10.sp) }
                            
                            Button(
                                onClick = { explorerPath = OpenMWPaths.LIBRARY_ROOT },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (explorerPath == OpenMWPaths.LIBRARY_ROOT) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.1f)
                                )
                            ) { Text("Library", fontSize = 10.sp) }

                            Button(
                                onClick = { explorerPath = OpenMWPaths.USER_RESOURCES },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (explorerPath == OpenMWPaths.USER_RESOURCES) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.1f)
                                )
                            ) { Text("Resources", fontSize = 10.sp) }
                        }

                        Box(modifier = Modifier.height(400.dp).fillMaxWidth()) {
                            FileBrowser(startPath = explorerPath)
                        }
                    }
                }
            }

            // iOS-style Floating Bottom Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .fillMaxWidth()
                    .height(64.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(32.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle Button (Launcher / Settings)
                    TextButton(
                        onClick = { showContent = !showContent },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (showContent) "🏠" else "⚙️",
                                fontSize = 20.sp
                            )
                            Text(
                                if (showContent) "Home" else "Settings",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    // Play Button (Prominent)
                    PlayBridge.onPlay?.let { play ->
                        Button(
                            onClick = play,
                            modifier = Modifier
                                .height(48.dp)
                                .weight(1.2f),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4CAF50),
                                contentColor = Color.White
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("▶ ", fontSize = 18.sp)
                                Text("PLAY", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    
                    // Import Button
                    TextButton(
                        onClick = { launcher.launch() },
                        enabled = !isImporting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📥", fontSize = 20.sp)
                            Text(
                                if (isImporting) "Wait..." else "Import",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }

                    // Logs Button (Beside Import)
                    TextButton(
                        onClick = { showLogs = !showLogs },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📜", fontSize = 20.sp)
                            Text(
                                "Logs",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Log Console Overlay
            LogConsole(showLogs = showLogs, onToggle = { showLogs = it })
        }
    }
}

@Composable
fun LogConsole(showLogs: Boolean, onToggle: (Boolean) -> Unit) {
    val logs = PlayBridge.logs

    Box(modifier = Modifier.fillMaxSize()) {
        if (showLogs) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp), // Height above the floating bar
                color = Color.Black.copy(alpha = 0.9f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Engine Logs", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Row {
                            TextButton(onClick = { logs.clear() }) {
                                Text("Clear", color = Color.Red)
                            }
                            IconButton(onClick = { onToggle(false) }) {
                                Text("✕", color = Color.White)
                            }
                        }
                    }
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = false
                    ) {
                        items(logs.size) { index ->
                            Text(
                                text = logs[index],
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color(0xFF4CAF50) // Terminal green
                                ),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
