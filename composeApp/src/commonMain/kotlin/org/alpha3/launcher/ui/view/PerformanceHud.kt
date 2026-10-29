package org.alpha3.launcher.ui.view

import coil3.compose.LocalPlatformContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.SystemInfo

@Composable
fun PerformanceHud(
    boxWidth: Float = 240f,
    boxHeight: Float = 160f,
    fontSize: Float = 10f
) {
    val cpuHistory by UIStateManager.cpuHistory.collectAsState()
    val gpuHistory by UIStateManager.gpuHistory.collectAsState()
    val cpuTempHistory by UIStateManager.cpuTempHistory.collectAsState()
    val gpuTempHistory by UIStateManager.gpuTempHistory.collectAsState()
    val memoryHistory by UIStateManager.memoryHistory.collectAsState()
    val frameTimes by UIStateManager.frameTimes.collectAsState()

    // Use boxWidth and boxHeight but subtract DraggableBox padding (8.dp * 2 = 16.dp)
    val effectiveWidth = (boxWidth - 16).coerceAtLeast(100f)
    val effectiveHeight = (boxHeight - 16).coerceAtLeast(80f)

    val cpuUsage = cpuHistory.lastOrNull() ?: 0
    val cpuTemp = cpuTempHistory.lastOrNull() ?: 0
    val gpuUsage = gpuHistory.lastOrNull() ?: 0
    val gpuTemp = gpuTempHistory.lastOrNull() ?: 0
    val hotThread = UIStateManager.cpuBusiestThread
    val usedMemMB = memoryHistory.lastOrNull() ?: 0L
    val totalMemMB = UIStateManager.totalMemoryMB

    // The last second of frames: the average rate, and the most recent interval
    var windowMs = 0f
    var windowFrames = 0
    for (i in frameTimes.indices.reversed()) {
        windowMs += frameTimes[i]
        windowFrames++
        if (windowMs >= 1000f) break
    }
    val fps = if (windowMs > 0f) windowFrames * 1000f / windowMs else 0f
    val lastFrameMs = frameTimes.lastOrNull() ?: 0f

    Box(modifier = Modifier.size(width = effectiveWidth.dp, height = effectiveHeight.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val time = remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                while (true) {
                    time.value = SystemInfo.services.clockTime()
                    kotlinx.coroutines.delay(1000)
                }
            }
            Text(time.value, color = Color.White, fontSize = (fontSize * 1.4f).sp, fontWeight = FontWeight.Bold)

            val clock = UIStateManager.gpuClockMhz
            HudLine(
                "GPU", Color(0xFF6BD08A), fontSize,
                "$gpuUsage%", loadColor(gpuUsage),
                listOfNotNull(if (gpuTemp > 0) "$gpuTemp°C" else null, if (clock > 0) "$clock MHz" else null).joinToString("  ")
            )
            HudLine(
                "CPU", Color(0xFF5BB5E8), fontSize,
                "$cpuUsage%", loadColor(maxOf(cpuUsage, hotThread)),
                // the busiest thread says more than the average over all cores: one core holds the game back long
                // before the phone as a whole looks busy
                "core $hotThread%" + if (cpuTemp > 0) "  $cpuTemp°C" else ""
            )
            HudLine(
                "IO", Color(0xFFA08CD8), fontSize,
                "R ${formatRate(UIStateManager.ioReadMiBs)}", Color.White,
                "W ${formatRate(UIStateManager.ioWriteMiBs)}"
            )
            HudLine(
                "RAM", Color(0xFFD87AA0), fontSize,
                if (totalMemMB > 0) "${formatGiB(usedMemMB)} / ${formatGiB(totalMemMB)} GiB" else "${usedMemMB} MB", Color.White,
                ""
            )
            HudLine(
                "FPS", Color(0xFFE5736B), fontSize,
                if (fps > 0f) "${fps.toInt()}" else "--", Color.White,
                if (lastFrameMs > 0f) "${formatMs(lastFrameMs)} ms" else ""
            )
            val renderer = UIStateManager.gpuModelName
            if (renderer.isNotEmpty() && renderer != "Unknown") {
                Text(renderer, color = Color(0xFFE5736B), fontSize = (fontSize * 0.85f).sp, maxLines = 1)
            }
            Text("Frametime", color = Color(0xFFE5736B), fontSize = (fontSize * 0.85f).sp)
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp)) {
                // tenths of a millisecond; a fixed floor keeps a steady 60 fps from filling the graph
                val peak = frameTimes.maxOrNull() ?: 0f
                Sparkline(
                    data = frameTimes.map { (it * 10).toInt() },
                    color = Color(0xFF6BD08A),
                    max = (maxOf(peak, 33.4f) * 10).toInt()
                )
            }
        }
    }
}

@Composable
private fun HudLine(label: String, labelColor: Color, fontSize: Float, value: String, valueColor: Color, detail: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = labelColor, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.2f))
        Text(value, color = valueColor, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.35f))
        Text(detail, color = Color.White, fontSize = fontSize.sp, modifier = Modifier.weight(0.45f), maxLines = 1)
    }
}

private fun formatRate(mibPerSecond: Float): String =
    if (mibPerSecond >= 10f) "${mibPerSecond.toInt()} MiB/s" else "${(mibPerSecond * 10).toInt() / 10.0} MiB/s"

private fun formatGiB(megabytes: Long): String = "${megabytes * 10 / 1024 / 10.0}"

private fun formatMs(value: Float): String = "${(value * 10).toInt() / 10.0}"

/** Green while there is headroom, through yellow and orange to red when a unit is close to its limit. */
internal fun loadColor(percent: Int): Color = when {
    percent < 50 -> Color(0xFF6BD08A)
    percent < 75 -> Color(0xFFF2D45C)
    percent < 90 -> Color(0xFFFFA500)
    else -> Color(0xFFE5736B)
}

@Composable
fun Sparkline(data: List<Int>, color: Color, max: Int) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (data.size < 2) return@Canvas

        val line = Path()
        val width = size.width
        val height = size.height
        val xStep = width / (data.size - 1)

        data.forEachIndexed { index, value ->
            val x = index * xStep
            val y = height - (value.toFloat() / max.coerceAtLeast(1) * height).coerceIn(0f, height)

            if (index == 0) {
                line.moveTo(x, y)
            } else {
                line.lineTo(x, y)
            }
        }

        // A faint fill under the line makes the level readable at a glance
        val area = Path().apply {
            addPath(line)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(path = area, color = color.copy(alpha = 0.15f))
        drawPath(
            path = line,
            color = color,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}
