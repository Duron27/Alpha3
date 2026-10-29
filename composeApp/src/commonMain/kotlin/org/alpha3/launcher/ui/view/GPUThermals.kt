package org.alpha3.launcher.ui.view

import org.alpha3.launcher.utils.SystemInfo
import org.alpha3.launcher.utils.formatDecimals
import org.alpha3.launcher.io.File
import org.alpha3.launcher.ui.controls.UIStateManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val HISTORY_SIZE = 60

/** One reading of the GPU. A field is null when the device does not give it. */
private class GpuSample(val tempC: Double?, val utilization: Int?, val clockMhz: Int?, val model: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalMonitorScreen() {
    var latest by remember { mutableStateOf<GpuSample?>(null) }
    var tempHistory by remember { mutableStateOf(listOf<Int>()) }
    var utilHistory by remember { mutableStateOf(listOf<Int>()) }
    var maxTemp by remember { mutableStateOf<Double?>(null) }
    var maxUtil by remember { mutableStateOf<Int?>(null) }
    // Starts on its own: the screen exists to be watched
    var isMonitoring by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isMonitoring) {
        if (isMonitoring) readGpuUtilization() // the busy counter is "since the last read": the first read only resets it
        while (isMonitoring) {
            delay(1000.milliseconds)
            try {
                // File reads off the main thread; the UI only gets the finished numbers
                val sample = withContext(Dispatchers.Default) { readGpuSample() }
                latest = sample
                errorMessage = null
                sample.tempC?.let { t ->
                    tempHistory = (tempHistory + t.toInt()).takeLast(HISTORY_SIZE)
                    maxTemp = maxOf(maxTemp ?: t, t)
                }
                sample.utilization?.let { u ->
                    utilHistory = (utilHistory + u).takeLast(HISTORY_SIZE)
                    maxUtil = maxOf(maxUtil ?: u, u)
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPU Monitor") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isMonitoring = !isMonitoring },
                containerColor = if (isMonitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (isMonitoring) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isMonitoring) "Pause" else "Start"
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = if (isMonitoring) Color.Green else Color.Gray, shape = CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = (if (isMonitoring) "Monitoring" else "Paused") + (latest?.model?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    fontSize = 14.sp
                )
            }

            val sample = latest
            ReadingCard(
                title = "GPU Temperature",
                value = sample?.tempC?.let { "${formatDecimals(it, 1)}°C" } ?: "--",
                valueColor = sample?.tempC?.let(::temperatureColor) ?: MaterialTheme.colorScheme.onSurface,
                history = tempHistory,
                graphMax = 100,
                graphColor = sample?.tempC?.let(::temperatureColor) ?: Color.Gray,
                footer = maxTemp?.let { "Highest this session: ${formatDecimals(it, 1)}°C" }
            )
            ReadingCard(
                title = "GPU Utilization",
                value = sample?.utilization?.let { "$it%" } ?: "--",
                valueColor = sample?.utilization?.let { utilizationColor(it) } ?: MaterialTheme.colorScheme.onSurface,
                history = utilHistory,
                graphMax = 100,
                graphColor = sample?.utilization?.let { utilizationColor(it) } ?: Color.Gray,
                footer = listOfNotNull(
                    sample?.clockMhz?.let { "Clock: $it MHz" },
                    maxUtil?.let { "Peak: $it%" }
                ).joinToString("  ·  ").ifEmpty { null }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The same nodes the Performance HUD uses; they are found automatically
                    Text(
                        text = HudSources.describe() + "\n" +
                            "• Updates every second; the last ${HISTORY_SIZE} readings are graphed",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (sample != null && sample.tempC == null && sample.utilization == null) {
                        Text(
                            text = "This device does not expose these nodes to apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingCard(
    title: String,
    value: String,
    valueColor: Color,
    history: List<Int>,
    graphMax: Int,
    graphColor: Color,
    footer: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, style = MaterialTheme.typography.displayMedium, color = valueColor)
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Sparkline(data = history, color = graphColor, max = graphMax)
            }
            footer?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Helper function to read process output
fun readProcessOutput(vararg command: String): String = SystemInfo.services.readProcessOutput(*command)

/**
 * Reads a sysfs value. Most nodes open directly, which costs nothing; a node the app may not open itself falls back to
 * `cat`, which is a process per read and so only a last resort.
 */
internal fun readSysfs(path: String): String {
    val file = File(path)
    if (file.exists() && file.canRead()) {
        runCatching { return file.readText().trim() }
    }
    return runCatching { readProcessOutput("cat", path).trim() }.getOrDefault("")
}

/** Thermal nodes report millidegrees on most devices and whole degrees on some; anything out of range is no reading. */
internal fun parseTempCelsius(raw: String): Double? {
    val value = raw.trim().toDoubleOrNull() ?: return null
    val celsius = if (value >= 1000) value / 1000 else value
    return celsius.takeIf { it > 0 && it < 150 }
}

/**
 * Where this device exposes GPU load and clock and the temperatures, found once: KGSL for Adreno, devfreq and the
 * vendor nodes for Mali. Nothing here is chosen by the user.
 */
internal object HudSources {
    private fun readable(path: String) = File(path).let { it.exists() && it.canRead() }

    /** The Adreno folder (/sys/class/kgsl/kgsl-3d0) when there is one. */
    val kgslDir: String? by lazy {
        File("/sys/class/kgsl").listFiles()?.filter { it.name.startsWith("kgsl-3d") }?.sortedBy { it.name }
            ?.firstOrNull { readable("${it.path}/gpu_busy_percentage") || readable("${it.path}/gpubusy") }?.path
    }

    /** The Mali devfreq device (clock and, on some kernels, load). */
    val maliDevfreq: String? by lazy {
        File("/sys/class/devfreq").listFiles()?.sortedBy { it.name }?.firstOrNull {
            val name = it.name.lowercase()
            name.contains("mali") || name.endsWith(".gpu") || name == "gpufreq"
        }?.path
    }

    /** Files that hold a Mali/other GPU's load; the first integer in them is the percentage. */
    val loadFile: String? by lazy {
        val candidates = mutableListOf<String>()
        maliDevfreq?.let { candidates += "$it/load" }
        File("/sys/devices/platform").listFiles()?.filter { it.name.lowercase().contains("mali") }
            ?.forEach { candidates += "${it.path}/utilization" }
        candidates += listOf(
            "/sys/class/misc/mali0/device/utilization", "/sys/kernel/gpu/gpu_busy", "/sys/kernel/ged/hal/gpu_utilization"
        )
        candidates.firstOrNull { readable(it) }
    }

    private fun zones(accept: (String) -> Boolean): List<String> =
        File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") }?.sortedBy { it.name }
            ?.filter {
                val type = readSysfs("${it.path}/type").lowercase()
                // "-step", "-lowf" and "-test" zones are throttling inputs, not sensors
                type.isNotEmpty() && !type.endsWith("-step") && !type.endsWith("lowf") && !type.endsWith("-test") && accept(type)
            }?.map { "${it.path}/temp" } ?: emptyList()

    val cpuTempFiles: List<String> by lazy {
        zones { it.startsWith("cpu") || it.contains("soc_thermal") || it.contains("mtktscpu") || it == "big" || it == "mid" || it == "little" }
            .ifEmpty { listOf("/sys/class/thermal/thermal_zone0/temp") }
    }

    val gpuTempFiles: List<String> by lazy {
        zones { it.contains("gpu") || it.contains("g3d") || it.contains("mali") }
    }

    fun describe(): String = buildString {
        append("GPU load: ").append(kgslDir ?: loadFile ?: "not exposed")
        append("\nGPU temperature: ").append(gpuTempFiles.firstOrNull() ?: kgslDir?.let { "$it/temp" } ?: "not exposed")
        append("\nCPU temperature: ").append(cpuTempFiles.size).append(" CPU sensors")
    }
}

private fun hottest(files: List<String>): Double? = files.mapNotNull { parseTempCelsius(readSysfs(it)) }.maxOrNull()

/** The hottest CPU sensor, in °C. */
internal fun readCpuTemperature(): Double? = hottest(HudSources.cpuTempFiles)

/** The hottest GPU sensor in °C; the Adreno folder's own value when the device has no GPU thermal zone. */
internal fun readGpuTemperature(): Double? =
    hottest(HudSources.gpuTempFiles) ?: HudSources.kgslDir?.let { parseTempCelsius(readSysfs("$it/temp")) }

/**
 * GPU utilization in percent. `gpu_busy_percentage` is a plain number; `gpubusy` is "busy total" for the time since it
 * was last read, so only one reader should use it (two readers each see a fraction of the interval). Mali reports it in
 * a vendor node, such as "12@400000000Hz".
 */
internal fun readGpuUtilization(): Int? {
    HudSources.kgslDir?.let { node ->
        readSysfs("$node/gpu_busy_percentage").removeSuffix("%").trim().toIntOrNull()?.let { return it.coerceIn(0, 100) }
        val parts = readSysfs("$node/gpubusy").split("\\s+".toRegex())
        if (parts.size < 2) return null
        val busy = parts[0].toDoubleOrNull() ?: return null
        val total = parts[1].toDoubleOrNull() ?: return null
        return if (total > 0) ((busy / total) * 100).toInt().coerceIn(0, 100) else 0
    }
    val file = HudSources.loadFile ?: return null
    return Regex("\\d+").find(readSysfs(file))?.value?.toIntOrNull()?.coerceIn(0, 100)
}

internal fun readGpuClockMhz(): Int? {
    HudSources.kgslDir?.let { return readSysfs("$it/clock_mhz").toIntOrNull() }
    HudSources.maliDevfreq?.let { dir ->
        readSysfs("$dir/cur_freq").toLongOrNull()?.let { return (it / 1_000_000L).toInt() }
    }
    return readSysfs("/sys/kernel/gpu/gpu_clock").toIntOrNull()
}

internal fun readGpuModel(): String =
    HudSources.kgslDir?.let { readSysfs("$it/gpu_model") }?.takeIf { it.isNotEmpty() }
        ?: HudSources.maliDevfreq?.let { "Mali (${File(it).name})" } ?: "Unknown"

private fun readGpuSample() = GpuSample(
    tempC = readGpuTemperature(),
    utilization = readGpuUtilization(),
    clockMhz = readGpuClockMhz(),
    model = readGpuModel()
)

private fun temperatureColor(celsius: Double): Color = when {
    celsius < 50 -> Color.Green
    celsius < 70 -> Color.Yellow
    celsius < 85 -> Color(0xFFFFA500) // Orange
    else -> Color.Red
}

// Color coding based on utilization
private fun utilizationColor(percent: Int): Color = when {
    percent < 30 -> Color.Green
    percent < 60 -> Color.Yellow
    percent < 85 -> Color(0xFFFFA500) // Orange
    else -> Color.Red
}
