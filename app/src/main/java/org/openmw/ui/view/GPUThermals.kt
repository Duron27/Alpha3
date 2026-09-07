package org.openmw.ui.view

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.time.Duration.Companion.milliseconds

class ThermalMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {

                Surface(modifier = Modifier.fillMaxSize()) {
                    ThermalMonitorScreen()
                }

        }
    }
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalMonitorScreen() {
    var gpuTemp by remember { mutableStateOf("--") }
    var gpuUtilization by remember { mutableStateOf("--") }
    var gpuBusyTime by remember { mutableStateOf("--") }
    var gpuTotalTime by remember { mutableStateOf("--") }
    var isMonitoring by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Reset GPU counters on first run
    LaunchedEffect(Unit) {
        readGpuStats() // First read resets counters
    }

    LaunchedEffect(isMonitoring) {
        while (isMonitoring) {
            try {
                // Read GPU temperature
                val tempResult = readProcessOutput("cat", "/sys/class/thermal/thermal_zone32/temp")
                val tempValue = tempResult.trim()
                gpuTemp = if (tempValue.isNotEmpty()) {
                    val celsius = tempValue.toDoubleOrNull()?.div(1000) ?: 0.0
                    String.format("%.1f°C", celsius)
                } else {
                    "--"
                }

                // Read GPU utilization (this gives values for the interval since last read)
                val gpuStats = readProcessOutput("cat", "/sys/class/kgsl/kgsl-3d0/gpubusy")
                val statsParts = gpuStats.trim().split("\\s+".toRegex())

                if (statsParts.size >= 2) {
                    gpuBusyTime = statsParts[0]
                    gpuTotalTime = statsParts[1]

                    val busy = statsParts[0].toDoubleOrNull() ?: 0.0
                    val total = statsParts[1].toDoubleOrNull() ?: 1.0

                    val utilization = if (total > 0) (busy / total) * 100 else 0.0
                    gpuUtilization = String.format("%.1f%%", utilization)

                    // Reset error state
                    errorMessage = null
                } else {
                    gpuUtilization = "--"
                    gpuBusyTime = "--"
                    gpuTotalTime = "--"
                }

            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
                gpuTemp = "--"
                gpuUtilization = "--"
            }

            // Update every second
            delay(1000.milliseconds)
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (isMonitoring) Color.Green else Color.Gray,
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMonitoring) "Monitoring Active" else "Monitoring Paused",
                    fontSize = 14.sp
                )
            }

            // GPU Temperature Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "GPU Temperature",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = gpuTemp,
                        style = MaterialTheme.typography.displayMedium,
                        color = getTemperatureColor(gpuTemp)
                    )
                }
            }

            // GPU Utilization Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "GPU Utilization",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = gpuUtilization,
                        style = MaterialTheme.typography.displayMedium,
                        color = getUtilizationColor(gpuUtilization)
                    )

                    // Show raw values
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Busy/Total Time (ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$gpuBusyTime / $gpuTotalTime",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Info section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Information",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Temperature is read from /sys/class/thermal/thermal_zone32/temp\n" +
                                "• GPU stats from /sys/class/kgsl/kgsl-3d0/gpubusy\n" +
                                "• Values update every second",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Error display
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
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

// Helper function to read process output
fun readProcessOutput(vararg command: String): String {
    return try {
        val process = ProcessBuilder(*command).start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }

        process.waitFor()
        output.toString()
    } catch (e: Exception) {
        "$e"
    }
}

// Function to read GPU stats (used for resetting counters)
private fun readGpuStats(): String {
    return readProcessOutput("cat", "/sys/class/kgsl/kgsl-3d0/gpubusy")
}

// Color coding based on temperature
@Composable
private fun getTemperatureColor(tempStr: String): Color {
    val temp = tempStr.replace("°C", "").toDoubleOrNull() ?: return MaterialTheme.colorScheme.onSurface

    return when {
        temp < 50 -> Color.Green
        temp < 70 -> Color.Yellow
        temp < 85 -> Color(0xFFFFA500) // Orange
        else -> Color.Red
    }
}

// Color coding based on utilization
@Composable
private fun getUtilizationColor(utilStr: String): Color {
    val util = utilStr.replace("%", "").toDoubleOrNull() ?: return MaterialTheme.colorScheme.onSurface

    return when {
        util < 30 -> Color.Green
        util < 60 -> Color.Yellow
        util < 85 -> Color(0xFFFFA500) // Orange
        else -> Color.Red
    }
}
