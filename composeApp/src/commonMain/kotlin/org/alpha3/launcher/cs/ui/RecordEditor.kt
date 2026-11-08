package org.alpha3.launcher.cs.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.cs.model.FLAG_BLOCKED
import org.alpha3.launcher.cs.model.FLAG_PERSISTENT
import org.alpha3.launcher.cs.model.FieldDef
import org.alpha3.launcher.cs.model.FieldSlot
import org.alpha3.launcher.cs.model.FieldType
import org.alpha3.launcher.cs.model.RECORD_DEFS
import org.alpha3.launcher.cs.model.RecordRow
import org.alpha3.launcher.cs.model.RecordState
import org.alpha3.launcher.cs.model.SubDef
import org.alpha3.launcher.cs.model.blankSub
import org.alpha3.launcher.cs.model.enumCode
import org.alpha3.launcher.cs.model.describe
import org.alpha3.launcher.cs.model.readField
import org.alpha3.launcher.cs.model.slots
import org.alpha3.launcher.cs.model.writeField
import org.alpha3.launcher.tools.openmw.Sub

/** One record as a form: each subrecord a card of its fields, in the order the file has them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordEditor(s: CsSession, row: RecordRow) {
    val doc = s.doc
    val def = RECORD_DEFS[row.tag] ?: return
    val record = remember(s.version, row) { doc.recordOf(row) }
    val subs = record.subs
    var cloning by remember { mutableStateOf(false) }

    fun commit(newSubs: List<Sub>, mergeKey: String? = null, flags: Long? = null) {
        doc.edit(row, newSubs, flags, mergeKey)
        s.changed()
        // a new ID moves the selection with it
        val name = newSubs.firstOrNull { it.tag == "NAME" }
        if (name != null) {
            val id = org.alpha3.launcher.tools.tes3cmd.recordId(row.tag, newSubs)
            if (id != row.id) s.selected = row.tag to id
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(record.displayId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${def.label} · " + when (row.state) {
                        RecordState.Base -> "from ${row.master?.let { doc.masters[it.file].name }}"
                        RecordState.Modified -> "changed in this plugin"
                        RecordState.New -> "new in this plugin"
                        RecordState.Deleted -> "deleted by this plugin"
                    },
                    color = stateColour.getValue(row.state), fontSize = 12.sp,
                )
            }
            if (def.sub("NAME") != null) SmallButton("Clone") { cloning = true }
            if (row.state != RecordState.Base) SmallButton("Revert") { doc.revert(row); s.changed() }
            if (row.state != RecordState.Deleted) SmallButton("Delete") { doc.delete(row); s.changed(); if (row.state == RecordState.New) s.selected = null }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(record.flags and FLAG_PERSISTENT != 0L, { commit(subs, flags = record.flags xor FLAG_PERSISTENT) }, label = { Text("References persist") })
            FilterChip(record.flags and FLAG_BLOCKED != 0L, { commit(subs, flags = record.flags xor FLAG_BLOCKED) }, label = { Text("Blocked") })
        }
        val preview = remember(s.version, row) { previewOf(s, record) }
        if (!preview.isEmpty) RecordPreview(s, preview, record.displayId)

        subs.forEachIndexed { index, sub ->
            val sd = def.sub(sub.tag)
            if (sub.tag == "DELE") return@forEachIndexed
            SubCard(s, sd, sub, index, removable = sd?.required != true && sub.tag != "NAME", onRemove = {
                commit(subs.filterIndexed { i, _ -> i != index })
            }) { newSub, field ->
                commit(subs.mapIndexed { i, o -> if (i == index) newSub else o }, mergeKey = "$index:$field")
            }
        }
        AddSubButton(def.subs.filter { d -> d.tag != "DELE" && d.layouts.isNotEmpty() && (d.repeats || subs.none { it.tag == d.tag }) }) { d ->
            // after the last subrecord that comes before it in the record's order
            val order = def.subs.map { it.tag }
            val rank = order.indexOf(d.tag)
            val at = subs.indexOfLast { order.indexOf(it.tag) in 0..rank } + 1
            commit(subs.toMutableList().also { it.add(at, blankSub(d)) })
        }
    }

    if (cloning) IdDialog("Clone ${record.displayId}", record.displayId + "_copy", onDismiss = { cloning = false }) { id ->
        cloning = false
        if (doc.rows(row.tag).any { it.id == id.lowercase() }) s.message = "$id already exists"
        else { doc.cloneAs(row, id); s.changed(); s.selected = row.tag to id.lowercase() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubCard(s: CsSession, def: SubDef?, sub: Sub, index: Int, removable: Boolean, onRemove: () -> Unit, onChange: (Sub, String) -> Unit) {
    val layout = slots(def, sub)
    Surface(color = Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(def?.label ?: sub.tag, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(sub.tag, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                if (removable) IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp)) }
            }
            if (layout == null) {
                Text("${sub.data.size} bytes, kept as they are", color = Color.Gray, fontSize = 12.sp)
            } else if (layout.size == 1) {
                FieldEditor(s, sub, layout[0], index, label = def?.label ?: layout[0].def.name, onChange)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (slot in layout) {
                        Box(Modifier.widthIn(min = 150.dp, max = if (slot.def.flags != null || slot.def.type is FieldType.Fixed && slot.size > 32) 520.dp else 240.dp)) {
                            FieldEditor(s, sub, slot, index, label = slot.def.name, onChange)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldEditor(s: CsSession, sub: Sub, slot: FieldSlot, index: Int, label: String, onChange: (Sub, String) -> Unit) {
    val codec = s.doc.codec
    val value = readField(sub, slot, codec)
    val f = slot.def
    fun set(v: Any) = onChange(writeField(sub, slot, v, codec), f.name)
    when {
        f.flags != null && value is Long -> Column {
            Text(label, fontSize = 12.sp, color = Color.LightGray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((bit, name) in f.flags) FilterChip(value and bit != 0L, { set(value xor bit) }, label = { Text(name, fontSize = 12.sp) })
            }
        }
        f.enum != null && value is Long -> EnumField(label, f, value) { set(it) }
        f.refs != null && value is String -> RefField(s, label, f, value, index) { set(it) }
        value is ByteArray -> Column {
            Text(label, fontSize = 12.sp, color = Color.LightGray)
            Text(describe(f, value), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
        }
        value is String -> {
            val multiline = f.type == FieldType.Text || slot.size > 64
            OutlinedTextField(
                value, { set(if (f.type is FieldType.Fixed) it.take(f.type.size - 1) else it) },
                label = { Text(label) }, singleLine = !multiline, minLines = if (multiline && slot.size > 200) 4 else 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> NumberField(label, value, f) { set(it) }
    }
}

/** A number as text: written back once it reads as a number of the field's kind. */
@Composable
private fun NumberField(label: String, value: Any, f: FieldDef, onSet: (Any) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value is Float) value.toString().removeSuffix(".0") else value.toString()) }
    val isFloat = f.type == FieldType.F32
    OutlinedTextField(
        text,
        { t ->
            text = t
            if (isFloat) t.toFloatOrNull()?.let(onSet)
            else t.toLongOrNull()?.let { v -> onSet(clampTo(f.type, v)) }
        },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (isFloat) KeyboardType.Decimal else KeyboardType.Number),
        isError = if (isFloat) text.toFloatOrNull() == null else text.toLongOrNull() == null,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun clampTo(t: FieldType, v: Long): Long = when (t) {
    FieldType.I8 -> v.coerceIn(-128, 127)
    FieldType.U8 -> v.coerceIn(0, 255)
    FieldType.I16 -> v.coerceIn(-32768, 32767)
    FieldType.U16 -> v.coerceIn(0, 65535)
    FieldType.I32 -> v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
    FieldType.U32 -> v.coerceIn(0, 0xffffffffL)
    else -> v
}

@Composable
private fun EnumField(label: String, f: FieldDef, value: Long, onSet: (Long) -> Unit) {
    val names = f.enum!!
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = Color.LightGray)
        Surface(
            shape = RoundedCornerShape(6.dp), color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth().clickable { open = true },
        ) { Text(describe(f, value), modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) }
        if (names.size <= 14) {
            DropdownMenu(open, { open = false }) {
                names.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { open = false; onSet(enumCode(f, i)) }) }
            }
        } else if (open) {
            PickerDialog(label, names, onDismiss = { open = false }) { picked ->
                open = false
                onSet(enumCode(f, names.indexOf(picked)))
            }
        }
    }
}

@Composable
private fun RefField(s: CsSession, label: String, f: FieldDef, value: String, index: Int, onSet: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value, { onSet(it) }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        IconButton(onClick = { picking = true }) { Icon(Icons.Default.Search, "Pick") }
    }
    if (picking) {
        val ids = remember(s.version) { s.doc.ids(f.refs!!) }
        PickerDialog("$label (${f.refs!!.joinToString { RECORD_DEFS[it]?.label ?: it }})", ids, onDismiss = { picking = false }) {
            picking = false
            onSet(it)
        }
    }
}

/** A searchable list to pick one of [items] from. */
@Composable
internal fun PickerDialog(title: String, items: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) { if (query.isBlank()) items else items.filter { it.contains(query, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${shown.size}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(360.dp)) {
                    items(shown) { item ->
                        Text(item, modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 9.dp, horizontal = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AddSubButton(choices: List<SubDef>, onAdd: (SubDef) -> Unit) {
    if (choices.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        SmallButton("+ Add") { open = true }
        DropdownMenu(open, { open = false }) {
            choices.forEach { d -> DropdownMenuItem(text = { Text("${d.label}  (${d.tag})") }, onClick = { open = false; onAdd(d) }) }
        }
    }
}
