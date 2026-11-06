package org.alpha3.launcher.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.alpha3.launcher.engine.Engine

// The performance HUD's "Inspect": the game's report (support/src/inspector.cpp) of one frame, what is in memory and
// the GPU's context, in tabs; every table sorts by any column and the filter narrows every table and list

/** A cell: a number (sorted as one) or text. */
private class InspectorCell(val number: Double?, val text: String)

private class InspectorSection(
    val title: String,
    val note: String,
    val lines: List<Pair<String, String>>,
    val columns: List<String>,
    val rows: List<List<InspectorCell>>,
    val sort: Int,
)

private class InspectorTab(val title: String, val sections: List<InspectorSection>)

private fun parseReport(json: String): List<InspectorTab> {
    val root = Json.parseToJsonElement(json).jsonObject
    return root["tabs"]!!.jsonArray.map { tab ->
        val t = tab.jsonObject
        InspectorTab(
            t["title"]!!.jsonPrimitive.content,
            t["sections"]!!.jsonArray.map { section ->
                val s = section.jsonObject
                InspectorSection(
                    title = s["title"]?.jsonPrimitive?.content ?: "",
                    note = s["note"]?.jsonPrimitive?.content ?: "",
                    lines = (s["lines"] as? JsonArray)?.map {
                        val pair = it.jsonArray
                        pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content
                    } ?: emptyList(),
                    columns = (s["columns"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                    rows = (s["rows"] as? JsonArray)?.map { row ->
                        row.jsonArray.map { cell ->
                            val p = cell as JsonPrimitive
                            if (p.isString) InspectorCell(null, p.content)
                            else InspectorCell(p.doubleOrNull, formatNumber(p.doubleOrNull ?: 0.0))
                        }
                    } ?: emptyList(),
                    sort = s["sort"]?.jsonPrimitive?.intOrNull ?: -1,
                )
            }
        )
    }
}

private fun formatNumber(value: Double): String {
    if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
        // thousands grouped: vertex and primitive counts run into millions
        val digits = value.toLong().toString()
        val negative = digits.startsWith("-")
        val body = digits.removePrefix("-")
        val grouped = body.reversed().chunked(3).joinToString(",").reversed()
        return if (negative) "-$grouped" else grouped
    }
    val abs = kotlin.math.abs(value)
    val decimals = if (abs >= 100) 1 else if (abs >= 1) 2 else 3
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val rounded = kotlin.math.round(value * factor) / factor
    val text = rounded.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) text else text.substring(0, minOf(text.length, dot + 1 + decimals))
}

/** Where the HUD's capture stands. */
enum class InspectorCapture { Idle, Capturing, Failed }

/**
 * Asks the game for a report and waits for it (a few frames, then the timer queries' results): the report's JSON, or
 * null when the game has none to give (not running, or no answer in 20 s).
 */
suspend fun captureInspectorReport(): String? = withContext(Dispatchers.Default) {
    if (!Engine.requestInspector()) return@withContext null
    repeat(80) {
        delay(250)
        Engine.inspectorReport()?.let { return@withContext it }
    }
    null
}

@Composable
fun InspectorDialog(report: String, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val platform = remember(report) { Engine.platformMemory() }
    val tabs = remember(report, platform) {
        val parsed = runCatching { parseReport(report) }.getOrElse {
            listOf(InspectorTab("Error", listOf(InspectorSection("The report could not be read", it.toString(), emptyList(), emptyList(), emptyList(), -1))))
        }
        if (platform.isEmpty()) parsed
        else parsed + InspectorTab(
            "Device",
            listOf(InspectorSection("The system's accounting of the game's process", "graphics is what the GPU driver holds for it: textures, buffers, render targets.", platform, emptyList(), emptyList(), -1))
        )
    }
    var selected by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // the filter field is not to take the focus (and open the keyboard) when the dialog opens
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(150)
            focusManager.clearFocus()
            keyboard?.hide()
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xF0101418),
            contentColor = Color.White,
        ) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Inspector", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Filter", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    )
                    TextButton(onClick = onRefresh) { Text("Capture again") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                val index = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
                ScrollableTabRow(
                    selectedTabIndex = index,
                    edgePadding = 0.dp,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                ) {
                    tabs.forEachIndexed { i, tab ->
                        Tab(selected = i == index, onClick = { selected = i }, text = { Text(tab.title, fontSize = 13.sp) })
                    }
                }
                tabs.getOrNull(index)?.let { InspectorTabContent(it, filter.trim()) }
            }
        }
    }
}

/** The tab's sections as one list: titles, notes, key and value lines, table headers and rows. */
private sealed interface InspectorItem {
    val key: String
}

private data class TitleItem(override val key: String, val section: InspectorSection, val shown: Int, val total: Int) : InspectorItem
private data class LineItem(override val key: String, val label: String, val value: String) : InspectorItem
private data class HeaderItem(override val key: String, val sectionIndex: Int, val section: InspectorSection) : InspectorItem
private data class RowItem(override val key: String, val cells: List<InspectorCell>, val weights: List<Float>) : InspectorItem

@Composable
private fun InspectorTabContent(tab: InspectorTab, filter: String) {
    // per table: the column it is sorted by and the direction; starts as the report says (largest first)
    val sorts = remember(tab) { mutableStateMapOf<Int, Pair<Int, Boolean>>() }
    val listState = rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }

    val items = remember(tab, filter, sorts.toMap()) {
        val out = mutableListOf<InspectorItem>()
        tab.sections.forEachIndexed { s, section ->
            val matches = { text: String -> filter.isEmpty() || text.contains(filter, ignoreCase = true) }
            if (section.columns.isNotEmpty()) {
                val (column, descending) = sorts[s] ?: (section.sort to true)
                var rows = section.rows.filter { row -> filter.isEmpty() || row.any { matches(it.text) } }
                if (column in section.columns.indices) {
                    val comparator = compareBy<List<InspectorCell>>(
                        { it.getOrNull(column)?.number },
                        { it.getOrNull(column)?.text?.lowercase() },
                    )
                    rows = rows.sortedWith(if (descending) comparator.reversed() else comparator)
                }
                if (rows.isEmpty() && filter.isNotEmpty() && !matches(section.title)) return@forEachIndexed
                out += TitleItem("t$s", section, rows.size, section.rows.size)
                val weights = columnWeights(section)
                out += HeaderItem("h$s", s, section)
                rows.forEachIndexed { r, row -> out += RowItem("r$s.$r", row, weights) }
            } else {
                val lines = section.lines.filter { matches(it.first) || matches(it.second) }
                if (lines.isEmpty() && filter.isNotEmpty()) return@forEachIndexed
                out += TitleItem("t$s", section, lines.size, section.lines.size)
                lines.forEachIndexed { l, (label, value) -> out += LineItem("l$s.$l", label, value) }
            }
        }
        out
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is TitleItem -> Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
                    val count = if (item.section.columns.isNotEmpty() || item.shown != item.total)
                        if (item.shown != item.total) "  (${item.shown} of ${item.total})" else ""
                    else ""
                    Text(item.section.title + count, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8FC7FF))
                    if (item.section.note.isNotEmpty())
                        Text(item.section.note, fontSize = 11.sp, color = Color(0xFFB0B8C0))
                }
                is LineItem -> Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(item.label, fontSize = 12.sp, color = Color(0xFFB0B8C0), modifier = Modifier.weight(0.4f))
                    Text(item.value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.6f))
                }
                is HeaderItem -> {
                    val section = item.section
                    val (column, descending) = sorts[item.sectionIndex] ?: (section.sort to true)
                    val weights = columnWeights(section)
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF242C34), RoundedCornerShape(4.dp)).padding(vertical = 4.dp)
                    ) {
                        section.columns.forEachIndexed { c, name ->
                            val arrow = if (c == column) (if (descending) " ▼" else " ▲") else ""
                            Text(
                                name + arrow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (c == column) Color(0xFFFFD27F) else Color.White,
                                textAlign = if (numericColumn(section, c)) TextAlign.End else TextAlign.Start,
                                maxLines = 2,
                                modifier = Modifier.weight(weights[c]).clickable {
                                    // a new column sorts largest first; the same one flips
                                    sorts[item.sectionIndex] = if (c == column) c to !descending else c to true
                                }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                is RowItem -> Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    item.cells.forEachIndexed { c, cell ->
                        Text(
                            cell.text,
                            fontSize = 11.sp,
                            fontFamily = if (cell.number != null) FontFamily.Monospace else FontFamily.Default,
                            textAlign = if (cell.number != null) TextAlign.End else TextAlign.Start,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(item.weights.getOrElse(c) { 1f }).padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

private fun numericColumn(section: InspectorSection, column: Int): Boolean =
    section.rows.firstOrNull()?.getOrNull(column)?.number != null

/** Text columns get the room (names and paths are long), numbers a narrow share. */
private fun columnWeights(section: InspectorSection): List<Float> =
    section.columns.indices.map { c ->
        if (!numericColumn(section, c)) {
            val longest = section.rows.take(200).maxOfOrNull { it.getOrNull(c)?.text?.length ?: 0 } ?: 0
            (longest / 8f).coerceIn(1f, 5f)
        } else 1f
    }

/** The HUD's button: captures, then shows the report full screen. */
@Composable
fun InspectorButton(fontSize: Float) {
    var capture by remember { mutableStateOf(InspectorCapture.Idle) }
    var report by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableIntStateOf(0) }

    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        capture = InspectorCapture.Capturing
        val result = captureInspectorReport()
        if (result != null) {
            report = result
            capture = InspectorCapture.Idle
        } else capture = InspectorCapture.Failed
    }

    Text(
        when (capture) {
            InspectorCapture.Idle -> "Inspect"
            InspectorCapture.Capturing -> "Capturing…"
            InspectorCapture.Failed -> "No report"
        },
        color = Color(0xFF8FC7FF),
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .clickable(enabled = capture != InspectorCapture.Capturing) { runs++ }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )

    report?.let {
        InspectorDialog(
            report = it,
            onRefresh = {
                report = null
                runs++
            },
            onDismiss = { report = null },
        )
    }
}
