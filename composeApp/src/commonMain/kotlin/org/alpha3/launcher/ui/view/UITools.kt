package org.alpha3.launcher.ui.view

import org.alpha3.launcher.utils.SystemInfo
import org.alpha3.launcher.utils.formatDecimals
import org.alpha3.launcher.utils.Log
import kotlinx.coroutines.IO
import org.alpha3.launcher.resources.backgroundbouncebw
import org.alpha3.launcher.resources.battery
import org.alpha3.launcher.resources.charging
import org.alpha3.launcher.resources.cpu_usage
import org.alpha3.launcher.resources.file_size
import org.alpha3.launcher.resources.free_space
import org.alpha3.launcher.resources.memory
import org.alpha3.launcher.resources.navmesh_complete
import org.alpha3.launcher.resources.navmesh_details
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.utils.PlatformContext
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.platform.LocalWindowInfo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.controls.UIStateManager.cpuUsageFlow
import org.alpha3.launcher.ui.controls.UIStateManager.customColor
import org.alpha3.launcher.ui.controls.UIStateManager.logMessagesFlow
import org.alpha3.launcher.ui.controls.UIStateManager.memoryInfoFlow
import org.alpha3.launcher.ui.controls.UIStateManager.userUI
import org.alpha3.launcher.ui.overlay.MemoryInfo
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.stringRes
import org.alpha3.launcher.io.File
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

data class LogEntry(
    val message: String,
    val textSize: Int,
    val textColor: Color
)

object LogRepository {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> get() = _logs

    // Many threads log at once, so the update has to be atomic, and the history is capped so the list
    // (copied on every entry) doesn't grow without bound during a big sync.
    private const val MAX_LOG_ENTRIES = 1500

    fun addLog(message: String, textSize: Int = 12, textColor: Color = Color.White) {
        val logEntry = LogEntry(message, textSize, textColor)
        _logs.update { (it + logEntry).takeLast(MAX_LOG_ENTRIES) }
    }
}

fun addCustomLog(message: String, textSize: Int = 12, textColor: Color = Color.White) {
    LogRepository.addLog(message, textSize, textColor)
}

@Composable
fun LogsBox(logs: StateFlow<List<LogEntry>>, fontSize: Float, boxWidth: Float, boxHeight: Float) {
    val logList by logs.collectAsState()
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto scroll to bottom when content changes
    LaunchedEffect(logList.size) {
        if (logList.isNotEmpty()) {
            coroutineScope.launch {
                val last = minOf(logList.size, lazyListState.layoutInfo.totalItemsCount) - 1
                if (last >= 0) try { lazyListState.animateScrollToItem(last) } catch (_: IndexOutOfBoundsException) {}
            }
        }
    }

    Box(
        modifier = Modifier
            .width(boxWidth.dp)
            .height(boxHeight.dp)
            .background(Color.Transparent)
            .padding(8.dp)
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            items(logList) { log ->
                Text(
                    text = log.message,
                    color = log.textColor,
                    fontSize = fontSize.sp,
                    style = TextStyle(fontWeight = FontWeight.Normal)
                )
            }
        }
    }
}

// Function to start logging updates
@DelicateCoroutinesApi
fun startLoggingUpdates() {
    GlobalScope.launch {
        while (UIStateManager.isLoggingEnabled || UIStateManager.showLogCat) {
            val logMessages = getMessages().joinToString("\n")
            logMessagesFlow.value = logMessages
            delay(2000.milliseconds)
        }
        logMessagesFlow.value = ""
    }
}

@DelicateCoroutinesApi
private var resourceUpdateJob: kotlinx.coroutines.Job? = null

@DelicateCoroutinesApi
fun startResourceInfoUpdates(context: PlatformContext) {
    if (resourceUpdateJob?.isActive == true) return

    resourceUpdateJob = GlobalScope.launch(Dispatchers.IO) {
        while (UIStateManager.isMemoryInfoEnabled || UIStateManager.isPerformanceHudEnabled) {
            // Existing memory & CPU
            val memoryInfo = getMemoryInfo(context)
            val cpuUsage = getCpuProcessUsage()
            UIStateManager.cpuBusiestThread = SystemInfo.services.cpuBusiestThread()

            // GPU readings (Qualcomm/Adreno specific – may be null/empty on non-KGSL devices)
            var gpuModel = "Unknown"
            var gpuTemp = "--°C"
            var gpuUtilization = "--%"
            var gpuClock = "--MHz"
            var cpuTemp = "--°C"

            try {
                // CPU Temperature
                cpuTemp = readCpuTemperature()?.let { "${it.toInt()}°C" } ?: "--°C"

                // GPU Model
                gpuModel = readGpuModel()

                // Temperature (the chosen node, then the KGSL one)
                gpuTemp = readGpuTemperature()?.let { "${it.toInt()}°C" } ?: "--°C"

                // Utilization
                gpuUtilization = "${readGpuUtilization() ?: 0}%"

                // Clock
                gpuClock = readGpuClockMhz()?.let { "$it MHz" } ?: "--MHz"
                UIStateManager.gpuClockMhz = gpuClock.removeSuffix(" MHz").trim().toIntOrNull() ?: 0
                UIStateManager.gpuModelName = gpuModel
                SystemInfo.services.ioRates().let {
                    UIStateManager.ioReadMiBs = it.first
                    UIStateManager.ioWriteMiBs = it.second
                }

            } catch (_: Exception) {}

            // Update Histories
            UIStateManager.totalMemoryMB = memoryInfo.totalBytes / (1024 * 1024)
            updateHistory(UIStateManager.cpuHistory, cpuUsage)
            updateHistory(UIStateManager.gpuHistory, gpuUtilization.removeSuffix("%").trim().toIntOrNull() ?: 0)
            updateHistory(UIStateManager.memoryHistory, (memoryInfo.totalBytes - memoryInfo.availableBytes) / (1024 * 1024))
            updateHistory(UIStateManager.cpuTempHistory, cpuTemp.removeSuffix("°C").trim().toIntOrNull() ?: 0)
            updateHistory(UIStateManager.gpuTempHistory, gpuTemp.removeSuffix("°C").trim().toIntOrNull() ?: 0)

            // Build the display string
            memoryInfoFlow.value = getDetailedSystemInfo(context, memoryInfo, cpuUsage, gpuModel, gpuUtilization, gpuTemp, gpuClock)
            cpuUsageFlow.value = cpuUsage

            delay(1000.milliseconds)
        }

        // Cleanup
        memoryInfoFlow.value = ""
        cpuUsageFlow.value = 0
    }
}

fun vibrate(context: PlatformContext, amplitude: Int = -1 /* default amplitude */, duration: Long = 100L) {
    SystemInfo.services.vibrate(amplitude, duration)
}

fun vibrateHelper(context: PlatformContext, amplitude: Int, duration: Long) {
    vibrate(context, amplitude, duration)
}

fun getDirectorySize(directory: File): Long {
    var totalSize: Long = 0
    if (directory.isDirectory) {
        val files = directory.listFiles()
        if (files != null) {
            for (file in files) {
                totalSize += try {
                    if (file.isDirectory) getDirectorySize(file) else file.length()
                } catch (_: Exception) {
                    // If we encounter a security exception, we ignore the size of that particular file/directory
                    0
                }
            }
        }
    }
    return totalSize
}

fun getCpuProcessUsage(pid: Int? = null): Int = SystemInfo.services.cpuProcessUsage(pid)

fun getAvailableStorageSpace(): String = humanReadableByteCountBin(SystemInfo.services.availableStorageBytes())

fun <T> updateHistory(flow: MutableStateFlow<List<T>>, newValue: T) {
    val current = flow.value.toMutableList()
    current.add(newValue)
    if (current.size > 30) current.removeAt(0)
    flow.value = current
}

fun getDetailedSystemInfo(
    context: PlatformContext,
    mem: MemoryInfo,
    cpu: Int,
    gpuModel: String,
    gpuUsage: String,
    gpuTemp: String,
    gpuClock: String
): String = SystemInfo.services.detailedSystemInfo(mem, cpu, gpuModel, gpuUsage, gpuTemp, gpuClock)

fun getMemoryInfo(context: PlatformContext): MemoryInfo = SystemInfo.services.memoryInfo()

fun humanReadableByteCountBin(bytes: Long): String {
    val unit = 1024
    if (bytes < unit) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    val pre = "KMGTPE"[exp - 1] + "i"
    return "${formatDecimals(bytes / unit.toDouble().pow(exp.toDouble()), 1)} ${pre}B"
}

fun getBatteryStatus(context: PlatformContext): String = SystemInfo.services.batteryStatus()

fun getFolderSize(folder: File): Long {
    if (!folder.exists() || !folder.isDirectory) return 0L
    var totalSize: Long = 0
    folder.listFiles()?.forEach { file ->
        if (file.isFile) {
            totalSize += file.length()
        } else if (file.isDirectory) {
            totalSize += getFolderSize(file)
        }
    }
    return totalSize
}

fun getMessages(): List<String> = SystemInfo.services.logcatMessages(UIStateManager.logcatLevel)

fun enableLogcat() = SystemInfo.services.enableLogcat(UIStateManager.logcatLevel)

    fun updateResolutionInConfig(width: Int, height: Int) {
    // Ensure the larger value is assigned to width
    val (adjustedWidth, adjustedHeight) = if (width > height) width to height else height to width

    val file = File(OpenMWPaths.SETTINGS_FILE)
    if (!file.exists()) return

    // Only a resolution of 0 means "not chosen yet"; any other value was set by the user and is left alone.
    val resolutionLine = Regex("""^(resolution [xy])\s*=\s*(\d+)\s*$""")
    val original = file.readText()
    val updated = original.split("\n").joinToString("\n") { line ->
        val match = resolutionLine.find(line.trimEnd('\r'))
        when {
            line.startsWith("# Width recommended for your device") -> "# Width recommended for your device = $adjustedWidth"
            line.startsWith("# Height recommended for your device") -> "# Height recommended for your device = $adjustedHeight"
            match != null && match.groupValues[2].toIntOrNull() == 0 -> {
                val value = if (match.groupValues[1] == "resolution x") adjustedWidth else adjustedHeight
                "${match.groupValues[1]} = $value"
            }
            else -> line
        }
    }
    if (updated != original) file.writeText(updated)
}

@Suppress("DEPRECATION")
@Composable
fun CustomProgressIndicator(progress: Float) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(100.dp)
    ) {
        CircularProgressIndicator(
            progress = progress,
            strokeWidth = 8.dp,
            modifier = Modifier.size(100.dp),
            trackColor = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
        )

        // Checkmark Image with Dynamic Alpha
        if (progress >= 0.95f) {
            val adjustedAlpha = (progress - 0.95f) / 0.05f
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Checkmark",
                tint = Color.Green.copy(alpha = adjustedAlpha),
                modifier = Modifier.size(60.dp) // Adjust size as needed
            )
        }
    }
}

fun hasInternetPermission(context: PlatformContext): Boolean = SystemInfo.services.hasInternetPermission()

@Suppress("DEPRECATION")
@Composable
fun ProgressWithNavmesh(onComplete: () -> Unit) {
    val progressFlow = remember { MutableStateFlow(0f) }
    val navmeshStatus = remember { MutableStateFlow("0.0") }
    val fileSizeFlow = remember { MutableStateFlow(0L) }
    val memoryInfoFlow = remember { MutableStateFlow(MemoryInfo("", "", "")) }
    val context = LocalPlatformContext.current
    val availableSpace = getAvailableStorageSpace()
    val logLinesFlow = remember { MutableStateFlow<List<String>>(emptyList()) }
    val scrollState = rememberScrollState()
    val progress by progressFlow.collectAsState()
    val fileSize by fileSizeFlow.collectAsState()
    val memoryInfo by memoryInfoFlow.collectAsState()
    val logLines by logLinesFlow.collectAsState()
    var detailedLogs by remember { mutableStateOf(false) }
    var cpuUsage by remember { mutableIntStateOf(0) }

    Row(
        modifier = Modifier
            .wrapContentSize(),
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(16.dp)
                    .background(Color.Black, shape = RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (progress < 1f) {
                    Text("${stringRes(Res.string.cpu_usage)}: $cpuUsage%", color = Color.White)
                    CustomProgressIndicator(progress = progress)
                    Spacer(modifier = Modifier.height(32.dp))
                    Text(
                        text = "Building Navmesh... ${(progress * 100).toInt()}%",
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "${stringResource(Res.string.file_size)}: ${fileSize / 1024} KB \n${stringResource(Res.string.free_space)}: $availableSpace bytes \n${stringResource(Res.string.memory)}: ${memoryInfo.usedMemory} / ${memoryInfo.totalMemory}",
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = stringResource(Res.string.navmesh_details), color = Color.White)
                        Switch(
                            checked = detailedLogs,
                            onCheckedChange = { detailedLogs = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.Green)
                        )
                    }
                } else {
                    Text(text = stringResource(Res.string.navmesh_complete), color = Color.White)
                    onComplete()
                }
            }
        }
        if (detailedLogs) {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .verticalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .background(Color.Black, shape = RoundedCornerShape(8.dp))
                ) {
                    logLines.forEach { line ->
                        Text(text = line, color = Color.White)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        //Log.d("ProgressWithNavmesh", "LaunchedEffect triggered")
        launch(Dispatchers.IO) {
            while (navmeshStatus.value != "Done") {
                val file = File("${OpenMWPaths.USER_DATA}/navmesh.db")
                if (file.exists()) {
                    fileSizeFlow.value = file.length()
                }

                // Update memory info
                memoryInfoFlow.value = getMemoryInfo(context)

                // Read navmeshtool.log and parse progress (since Env is process-local)
                val logFile = File("${OpenMWPaths.ACTIVE_CONFIG}/navmeshtool.log")
                if (logFile.exists()) {
                    val lines = try { logFile.readLines() } catch (_: Exception) { emptyList() }
                    logLinesFlow.value = lines

                    // 1. Check for success message to end loop
                    if (lines.any { it.contains("runNavMeshTool sucess") }) {
                        navmeshStatus.value = "Done"
                    } else {
                        // 2. Parse percentage from the latest generation log line
                        lines.findLast { it.contains("navmesh tiles are generated") }?.let { line ->
                            val regex = """\(([\d.]+)%\)""".toRegex()
                            regex.find(line)?.groupValues?.get(1)?.let { percent ->
                                navmeshStatus.value = percent
                                progressFlow.value = percent.toFloat() / 100.0f
                            }
                        }
                    }
                }

                val navmeshPid = SystemInfo.services.navmeshProcessId()

                // Update CPU usage using the correct PID
                val usage = getCpuProcessUsage(navmeshPid)
                withContext(Dispatchers.Main) {
                    cpuUsage = usage
                }

                delay(1000.milliseconds)
            }
            progressFlow.value = 1f

        }
    }
    LaunchedEffect(logLines.size) { scrollState.animateScrollTo(scrollState.maxValue) }
}

@Composable
fun BackgroundAnimation() {
    val context = LocalPlatformContext.current
    val backgroundAnimation by GameFilesPreferences.getPreference(GameFilesPreferences.BACKGROUND_ANIMATION_KEY, "BouncingBackground").collectAsState(initial = "BouncingBackground")
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
    ) {
        when (backgroundAnimation) {
            "BouncingBackground" -> BouncingBackground()
            "RotatingImageBackground" -> RotatingImageBackground()
            "CircularBackground" -> CircularBackground()
            else -> NoneBackground()
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun NoneBackground() {
    val context = LocalPlatformContext.current
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val image: Painter = when (codeGroupOption) {
        "OpenMW" -> rememberAsyncImagePainter(model = "file://${userUI}/backgroundbouncebw.jpg")
        "UQM" -> rememberAsyncImagePainter(model = "file://${userUI}/starmap.jpg")
        else -> painterResource(Res.drawable.backgroundbouncebw)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .background(color = customColor)
        )
    }
}

@Suppress("DEPRECATION")
@Composable
fun RotatingImageBackground() {
    val context = LocalPlatformContext.current
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val image: Painter = when (codeGroupOption) {
        "OpenMW" -> rememberAsyncImagePainter(model = "file://${userUI}/backgroundbouncebw.jpg")
        "UQM" -> rememberAsyncImagePainter(model = "file://${userUI}/starmap.jpg")
        else -> painterResource(Res.drawable.backgroundbouncebw)
    }

    // Create an infinite transition for rotation
    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 10000, easing = LinearEasing),
            RepeatMode.Restart
        )
    )

    // Adjust the scale factor here to set the zoom level
    val zoomFactor = 3f // Example zoom factor

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = zoomFactor
                    scaleY = zoomFactor
                }
                .background(color = Color.Black)
        )
    }
}

@Suppress("DEPRECATION")
@Composable
fun BouncingBackground() {
    val context = LocalPlatformContext.current
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val image: Painter = when (codeGroupOption) {
        "OpenMW" -> rememberAsyncImagePainter(model = "file://${userUI}/backgroundbouncebw.jpg")
        "UQM" -> rememberAsyncImagePainter(model = "file://${userUI}/starmap.jpg")
        else -> painterResource(Res.drawable.backgroundbouncebw)
    }

    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidth = containerSize.width
    val screenHeight = containerSize.height

    val imageWidth = 2000 // Replace with your image width
    val imageHeight = 2337 // Replace with your image height

    var offset: Offset by remember { mutableStateOf(Offset.Zero) }
    val xDirection by remember { mutableFloatStateOf(1f) }
    val yDirection by remember { mutableFloatStateOf(1f) }

    // Adjust this value to increase the distance
    val stepSize = 1f

    LaunchedEffect(Unit) {
        while (true) {
            offset = Offset(
                x = (offset.x + xDirection * stepSize) % screenWidth,
                y = (offset.y + yDirection * stepSize) % screenHeight
            )

            delay(16L.milliseconds) // Update every frame (approx 60fps)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = image,
            contentDescription = null,
            modifier = Modifier
                .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .size(imageWidth.dp, imageHeight.dp) // Convert Int to Dp
                .scale(6f) // Scale the image up by a factor of 6
                .background(color = Color.Black))
    }
}

@Suppress("DEPRECATION")
@Composable
fun CircularBackground() {
    val context = LocalPlatformContext.current
    val codeGroupOption by GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collectAsState(initial = "OpenMW")
    val image: Painter = when (codeGroupOption) {
        "OpenMW" -> rememberAsyncImagePainter(model = "file://${userUI}/backgroundbouncebw.jpg")
        "UQM" -> rememberAsyncImagePainter(model = "file://${userUI}/starmap.jpg")
        else -> painterResource(Res.drawable.backgroundbouncebw)
    }

    val containerSize = LocalWindowInfo.current.containerSize
    val screenWidth = containerSize.width
    val screenHeight = containerSize.height

    val imageWidth = 2000 // Replace with your image width
    val imageHeight = 2337 // Replace with your image height

    var offset: Offset by remember { mutableStateOf(Offset.Zero) }
    var angle by remember { mutableFloatStateOf(0f) }
    val radius = 1000f // Adjust the radius of the circular motion
    val speed = 0.0020f

    LaunchedEffect(Unit) {
        while (true) {
            offset = Offset(
                x = screenWidth / 2f + radius * cos(angle),
                y = screenHeight / 2f + radius * sin(angle)
            )
            angle += speed // Adjust the speed by changing this value

            delay(16L.milliseconds) // Update every frame (approx 60fps)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = image,
            contentDescription = null,
            modifier = Modifier
                .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .size(imageWidth.dp, imageHeight.dp) // Convert Int to Dp
                .scale(6f) // Scale the image up by a factor of 6
                .background(color = Color.Black)
        )
    }
}
