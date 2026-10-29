package org.alpha3.launcher.crash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.utils.MToast
import org.alpha3.launcher.utils.PlatformContext

private sealed interface Row2 {
    class Header(val key: String, val title: String, val open: Boolean, val count: Int) : Row2
    class Line(val text: String, val color: Color) : Row2
}

private val textColor = Color(0xFFE0E0E0)
private val dimColor = Color(0xFF8A8A8A)
private val errorColor = Color(0xFFFF6B6B)
private val warnColor = Color(0xFFFFC857)
private val goodColor = Color(0xFF7FD6A0)
private val headerColor = Color(0xFF8AB4F8)

/** The previous crash: what happened, where (with function names from the bundled symbols), and the logs around it. */
@Composable
fun CrashViewerDialog(context: PlatformContext, onClose: () -> Unit) {
    var data by remember { mutableStateOf<CrashData?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var exported by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.Default) { runCatching { CrashLoader.load(context) }.getOrNull() }
        loading = false
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xF2101214),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Last crash",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val current = data
                    if (current != null && !current.isEmpty) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(CrashLoader.toText(current)))
                            MToast("Crash report copied")
                        }) { Text("Copy") }
                        TextButton(onClick = {
                            scope.launch {
                                val path = withContext(Dispatchers.Default) { CrashExport.export(context, current) }
                                exported = path
                                MToast(if (path != null) "Saved $path" else "Could not write the zip", isLong = true)
                            }
                        }) { Text("Export zip") }
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                exported?.let { Text("Exported to $it", color = goodColor, fontSize = 11.sp) }
                Box(Modifier.fillMaxSize()) {
                    val current = data
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        current == null || current.isEmpty -> Text(
                            "No crash has been recorded.", color = dimColor, modifier = Modifier.align(Alignment.Center),
                        )
                        else -> CrashContent(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashContent(data: CrashData) {
    // Sections the user opened or closed, by key; the rest keep their default
    var toggled by remember { mutableStateOf(emptySet<String>()) }
    val rows = remember(data, toggled) { buildRows(data, toggled) }

    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(rows) { row ->
                when (row) {
                    is Row2.Header -> Text(
                        (if (row.open) "▼ " else "▶ ") + row.title + if (row.count > 0) "  (${row.count})" else "",
                        color = headerColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clickable { toggled = if (row.key in toggled) toggled - row.key else toggled + row.key },
                    )
                    is Row2.Line -> Text(
                        row.text,
                        color = row.color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

private fun logColor(line: String): Color = when {
    line.contains(" E]") || line.contains("Fatal") -> errorColor
    line.contains(" W]") -> warnColor
    else -> textColor
}

private fun buildRows(data: CrashData, toggled: Set<String>): List<Row2> {
    val rows = ArrayList<Row2>()

    fun section(key: String, title: String, defaultOpen: Boolean, count: Int = 0, body: MutableList<Row2>.() -> Unit) {
        val open = defaultOpen != (key in toggled)
        rows += Row2.Header(key, title, open, count)
        if (open) rows.body()
    }

    fun MutableList<Row2>.lines(list: List<String>, color: (String) -> Color = { textColor }) =
        list.forEach { add(Row2.Line(it, color(it))) }

    section("summary", "Summary", true) {
        for ((label, value) in CrashLoader.summary(data)) {
            add(Row2.Line("${label.padEnd(15)} $value", if (label == "Signal" || label == "Abort message" || label == "Cause") errorColor else textColor))
        }
    }

    data.crashingThread?.let { thread ->
        section("backtrace", "Backtrace of ${thread.name} (tid ${thread.id})", true, thread.frames.size) {
            if (data.symbolsMissing.isNotEmpty() && thread.frames.any { it.function.isEmpty() }) {
                add(Row2.Line("No symbols bundled for: ${data.symbolsMissing.joinToString()}", dimColor))
            }
            thread.frames.forEach { frame ->
                val color = when (frame.source) {
                    "symbols" -> goodColor
                    "system" -> textColor
                    else -> dimColor
                }
                add(Row2.Line("${frame.label.padEnd(4)} ${frame.text()}", color))
            }
        }
    }

    if (data.registers.isNotEmpty()) {
        section("registers", "Registers", false, data.registers.size) {
            lines(data.registers.chunked(2).map { pair ->
                pair.joinToString("   ") { (name, value) -> "${name.padEnd(4)} 0x${value.toString(16).padStart(16, '0')}" }
            })
        }
    }

    val others = data.threads.filter { !it.crashed }
    if (others.isNotEmpty()) {
        section("threads", "Other threads", false, others.size) {
            for (thread in others) {
                add(Row2.Line("tid ${thread.id}  ${thread.name}", headerColor))
                thread.frames.take(8).forEach { add(Row2.Line("   ${it.label.padEnd(4)} ${it.text()}", if (it.source == "symbols") goodColor else textColor)) }
            }
        }
    }

    if (data.openmwLogTail.isNotEmpty()) {
        section("log", "OpenMW log (end of openmw.log)", true, data.openmwLogTail.size) {
            lines(data.openmwLogTail, ::logColor)
        }
    }
    data.rendererStatus?.let { status ->
        section("renderer", "Renderer status", false) { lines(status.lines()) }
    }
    data.exitRecord?.let { record ->
        section("exit", "System exit record", false) { lines(record.lines()) }
    }
    data.javaCrash?.let { java ->
        section("java", "Java crash log", true) { lines(java.lines(), ::logColor) }
    }
    data.native?.let { native ->
        section("native", "Native crash report (raw)", false, native.lines.size) { lines(native.lines.map { it.trimEnd() }) }
    }
    return rows
}
