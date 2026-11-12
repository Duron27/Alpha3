package org.alpha3.launcher.benchmark

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.utils.MToast
import org.alpha3.launcher.utils.Platform

/**
 * Shown when the launcher opens after a fly-through: the run's average fps and table, and the settings to try for the
 * next run, the biggest gain first. "Run again" starts the benchmark with the chosen ones, "Apply to my settings" writes
 * them into the player's settings.cfg, "Done" puts the results away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BenchmarkResultsHost() {
    var result by remember { mutableStateOf(FlyTuning.pendingResult()) }
    val file = result ?: return
    val fps = remember(file) { FlyTuning.overallFps(file) }
    val table = remember(file) { FlyTuning.table(file) }
    val chosen = remember { mutableStateMapOf<String, String>().apply { putAll(FlyTuning.loadTuning()) } }
    var note by remember { mutableStateOf<String?>(null) }

    fun close() {
        FlyTuning.clearPending()
        result = null
    }

    AlertDialog(
        onDismissRequest = { close() },
        title = {
            Column {
                Text("Fly-through results", fontSize = 18.sp)
                Text(
                    fps?.let { "${(it * 10).toInt() / 10.0} fps average" } ?: "No frames measured",
                    fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCAA560)
                )
                Text(
                    if (chosen.isEmpty()) "Fixed benchmark settings" else "With ${chosen.size} setting(s) changed",
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f)
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    table, fontFamily = FontFamily.Monospace, fontSize = 10.sp, softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Try for the next run (biggest gain first):", fontWeight = FontWeight.SemiBold)
                FlyTuning.tunables.forEach { t ->
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(t.title, fontWeight = FontWeight.SemiBold)
                        Text(t.why, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            t.options.forEachIndexed { i, o ->
                                val selected = (chosen[t.key] ?: t.options.first().value) == o.value
                                FilterChip(
                                    selected = selected,
                                    onClick = { if (i == 0) chosen.remove(t.key) else chosen[t.key] = o.value },
                                    label = { Text(o.label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
                note?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val (w, h) = Platform.features.nativeScreenSize()
                    FlyTuning.applyToPlayer(chosen.toMap(), w, h)
                    MToast(if (chosen.isEmpty()) "Nothing changed to apply" else "Applied to your settings")
                }, enabled = chosen.isNotEmpty()) { Text("Apply to my settings") }
                Button(onClick = {
                    FlyTuning.saveTuning(chosen.toMap())
                    FlyTuning.clearPending()
                    note = Platform.features.startFlyBenchmark(chosen.toMap())
                    if (note == null) result = null
                }) { Text("Run again") }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }) { Text("Done") }
        }
    )
}
