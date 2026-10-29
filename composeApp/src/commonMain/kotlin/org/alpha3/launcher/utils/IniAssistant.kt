package org.alpha3.launcher.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.files.readTextFile
import org.alpha3.launcher.files.readTextFile2
import org.alpha3.launcher.files.writeTextFile
import org.alpha3.launcher.globals.PlayBridge
import org.alpha3.launcher.globals.darkGray
import org.alpha3.launcher.paths.OpenMWPaths

/** A decimal setting. Keeps the file's own text so values like 0.100000 are shown and rewritten untouched. */
internal data class IniFloat(val text: String)

private val IntPattern = Regex("^-?\\d+$")
private val FloatPattern = Regex("^-?((\\d+\\.\\d*|\\.\\d+)([eE][+-]?\\d+)?|\\d+[eE][+-]?\\d+)$")

/** Bool, Int and decimal values get their own editor; anything else stays a String. */
private fun parseIniValue(value: String): Any = when {
    value.equals("true", ignoreCase = true) -> true
    value.equals("false", ignoreCase = true) -> false
    IntPattern.matches(value) -> value.toIntOrNull() ?: value
    FloatPattern.matches(value) -> IniFloat(value)
    else -> value
}

internal fun readIniValues(): Map<String, List<Triple<String, Any, String?>>> {
    val settings = linkedMapOf<String, MutableList<Triple<String, Any, String?>>>()
    var currentSection: String? = null

    // Define your blacklist here
    val blacklist = setOf("key1", "key2", "key3")
    val text = readTextFile(OpenMWPaths.SETTINGS_FILE) ?: return emptyMap()

    val lines = text.lines()

    for (i in lines.indices) {
        val line = lines[i].trim()
        when {
            // Comments can contain '=' (e.g. "# 0 = Fullscreen"), so they must never be read as settings.
            line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
            line.startsWith("[") && line.endsWith("]") -> {
                currentSection = line.substring(1, line.length - 1).trim()
                settings.getOrPut(currentSection) { mutableListOf() }
            }
            "=" in line -> {
                val section = currentSection ?: continue
                val parts = line.split("=", limit = 2).map { it.trim() }
                val key = parts[0]
                if (key in blacklist) continue
                val entries = settings.getOrPut(section) { mutableListOf() }
                // writeIniValue edits the first match, so the first one is the one to show.
                if (entries.any { it.first == key }) continue

                // The comment sits directly above the key. Blank lines are skipped until it starts;
                // after that a blank line ends it, so an earlier block can't leak in.
                val commentLines = mutableListOf<String>()
                var j = i - 1
                while (j >= 0) {
                    val prevLine = lines[j].trim()
                    if (prevLine.startsWith("#")) {
                        commentLines.add(0, prevLine.substring(1).trim())
                    } else if (prevLine.isEmpty() && commentLines.isEmpty()) {
                        // still looking for the comment
                    } else {
                        break
                    }
                    j--
                }
                // Hard-wrapped comment lines are one paragraph; let the UI do the wrapping.
                val comment = commentLines.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
                entries.add(Triple(key, parseIniValue(parts[1]), comment))
            }
        }
    }
    return settings
}


fun writeIniValue(section: String, key: String, value: Any, comment: String? = null) {
    val path = OpenMWPaths.SETTINGS_FILE
    val lines = readTextFile2(path).lines().toMutableList()
    var sectionFound = false
    var keyFound = false
    var sectionEndIndex = lines.size

    for (i in lines.indices) {
        val line = lines[i].trim()
        if (line.startsWith("[") && line.endsWith("]")) {
            if (sectionFound) {
                sectionEndIndex = i
                break
            }
            if (line.substring(1, line.length - 1).trim() == section) {
                sectionFound = true
            }
        } else if (sectionFound && "=" in line) {
            val lineKey = line.split("=", limit = 2)[0].trim()
            if (lineKey == key) {
                lines[i] = "${key.trim()} = ${value.toString().trim()}"
                keyFound = true
                if (comment != null) {
                    val formattedComment = if (comment.startsWith("#")) comment.trim() else "# ${comment.trim()}"
                    // Check if comment already exists above (either directly or separated by other comments)
                    var existingCommentIndex = -1
                    for (j in (i - 1) downTo 0) {
                        val prevLine = lines[j].trim()
                        if (prevLine == formattedComment) {
                            existingCommentIndex = j
                            break
                        }
                        if (!prevLine.startsWith("#") && prevLine.isNotEmpty()) break
                    }

                    if (existingCommentIndex == -1) {
                        lines.add(i, formattedComment)
                    }
                }
                break
            }
        }
    }

    if (!sectionFound) {
        lines.add("[${section.trim()}]")
        if (comment != null) {
            val formattedComment = if (comment.startsWith("#")) comment.trim() else "# ${comment.trim()}"
            lines.add(formattedComment)
        }
        lines.add("${key.trim()} = ${value.toString().trim()}")
    } else if (!keyFound) {
        if (comment != null) {
            val formattedComment = if (comment.startsWith("#")) comment.trim() else "# ${comment.trim()}"
            lines.add(sectionEndIndex, formattedComment)
            lines.add(sectionEndIndex + 1, "${key.trim()} = ${value.toString().trim()}")
        } else {
            lines.add(sectionEndIndex, "${key.trim()} = ${value.toString().trim()}")
        }
    }
    writeTextFile(path, lines.joinToString("\n"))
}

@Composable
fun IniSettings(refreshKey: Int = 0) {
    var settings by remember(refreshKey) { mutableStateOf(readIniValues()) }
    var searchQuery by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val translator = LocalTextTranslator.current
    val translationChecked = translator?.isEnabled() ?: false

    val filteredSettings = remember(settings, searchQuery) {
        if (searchQuery.isBlank()) {
            settings
        } else {
            settings.mapValues { (_, sectionSettings) ->
                sectionSettings.filter { (key, _, comment) ->
                    key.contains(searchQuery, ignoreCase = true) ||
                            (comment?.contains(searchQuery, ignoreCase = true) == true)
                }
            }.filterValues { it.isNotEmpty() }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            placeholder = { Text("Search settings...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Gray
            )
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filteredSettings.entries.forEach { (section, sectionSettings) ->
                key(section) {
                    IniSectionCard(
                        section = section,
                        sectionSettings = sectionSettings,
                        translationChecked = translationChecked,
                        onValueChange = { settingKey, newValue ->
                            writeIniValue(section, settingKey, newValue, null)
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            settings = readIniValues()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun IniSectionCard(
    section: String,
    sectionSettings: List<Triple<String, Any, String?>>,
    translationChecked: Boolean,
    onValueChange: (String, Any) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = darkGray.copy(alpha = 0.9f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White
                )
            }

            if (isExpanded) {
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                Column(modifier = Modifier.padding(8.dp)) {
                    sectionSettings.forEachIndexed { index, (settingKey, value, comment) ->
                        // Keyed so a search filter doesn't hand one setting's remembered text to another.
                        key(settingKey) {
                            IniSettingItem(
                                propertyKey = settingKey,
                                value = value,
                                comment = comment,
                                translationChecked = translationChecked,
                                onValueChange = { onValueChange(settingKey, it) }
                            )
                        }
                        if (index < sectionSettings.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = Color.Gray.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IniSettingItem(
    propertyKey: String,
    value: Any,
    comment: String?,
    translationChecked: Boolean,
    onValueChange: (Any) -> Unit
) {
    val extractedKey = propertyKey.substringAfterLast('.')
    val translator = LocalTextTranslator.current

    var translatedKey by remember(extractedKey) { mutableStateOf(extractedKey) }
    var translatedComment by remember(comment) { mutableStateOf(comment ?: "") }

    if (translationChecked) {
        translator?.Translate(extractedKey) { translatedKey = it }
        if (comment != null) {
            translator?.Translate(comment) { translatedComment = it }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = translatedKey,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            when (value) {
                is Boolean -> {
                    Switch(
                        checked = value,
                        onCheckedChange = { onValueChange(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                is Int -> IniNumberField(original = value.toString(), isInt = true, onCommit = onValueChange)
                is IniFloat -> IniNumberField(original = value.text, isInt = false, onCommit = onValueChange)
                else -> Unit
            }
        }

        // The comment gets the full width (not the sliver left beside the editor) and folds when long.
        if (comment != null) {
            IniComment(translatedComment)
        }

        if (value !is Boolean && value !is Int && value !is IniFloat) {
            IniTextField(original = value.toString(), onCommit = onValueChange)
        }
    }
}

@Composable
private fun IniComment(text: String) {
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.LightGray,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clickable(enabled = overflows || expanded) { expanded = !expanded }
    )
    if (overflows || expanded) {
        Text(
            text = if (expanded) "Show less" else "Show more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded }.padding(top = 2.dp)
        )
    }
}

/** Keeps only characters that can be part of the number: digits, a leading '-', and for decimals one '.'. */
private fun filterNumberInput(input: String, isInt: Boolean): String {
    val out = StringBuilder()
    for (c in input) {
        when {
            c in '0'..'9' -> out.append(c)
            c == '-' && out.isEmpty() -> out.append(c)
            c == '.' && !isInt && out.indexOf(".") == -1 -> out.append(c)
        }
    }
    return out.toString()
}

/**
 * The text to write for [input], or null if it isn't a complete number of the right kind.
 * Integers stay integers, and decimals always keep a '.' so the setting can't flip type on the next read.
 */
private fun normalizeNumber(input: String, isInt: Boolean): String? {
    if (isInt) return if (IntPattern.matches(input) && input.toIntOrNull() != null) input else null
    val body = input.removePrefix("-")
    if (body.isEmpty() || body == ".") return null
    val sign = if (input.startsWith("-")) "-" else ""
    var number = body
    if (number.startsWith(".")) number = "0$number"
    if (number.endsWith(".")) number += "0"
    if ('.' !in number) number += ".0"
    return sign + number
}

/** Edits a number in place and writes it when the field loses focus or Done is pressed. */
@Composable
private fun IniNumberField(original: String, isInt: Boolean, onCommit: (Any) -> Unit) {
    var text by remember(original) { mutableStateOf(original) }
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isError = normalizeNumber(text, isInt) == null

    fun commit() {
        val normalized = normalizeNumber(text, isInt)
        when {
            normalized == null -> text = original // incomplete or invalid, keep what the file has
            normalized != original -> onCommit(normalized)
            else -> text = normalized
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = filterNumberInput(it, isInt) },
        modifier = Modifier
            .width(110.dp)
            .onFocusChanged { state ->
                if (hasFocus && !state.isFocused) commit()
                hasFocus = state.isFocused
            },
        isError = isError,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = if (isInt) KeyboardType.Number else KeyboardType.Decimal
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Gray
        )
    )
}

/** Free text for settings that are neither a switch nor a number; written when the field loses focus. */
@Composable
private fun IniTextField(original: String, onCommit: (Any) -> Unit) {
    var text by remember(original) { mutableStateOf(original) }
    var hasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = text,
        onValueChange = { text = it.replace("\n", " ") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .onFocusChanged { state ->
                if (hasFocus && !state.isFocused) {
                    val trimmed = text.trim()
                    if (trimmed != original) onCommit(trimmed)
                }
                hasFocus = state.isFocused
            },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Gray
        )
    )
}

/** Collapsible "Engine Settings" card used by the launcher screen; hosts the shared [IniSettings]. */
@Composable
fun ReadAndDisplayIniValues() {
    var isExpanded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1E))
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Engine Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            Text(if (isExpanded) "Hide" else "Show", color = Color(0xFF0A84FF), fontSize = 16.sp)
        }

        if (isExpanded) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
            // Bounded + scrollable so it works inside the non-scrollable launcher column.
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                IniSettings(refreshKey)
            }

            PlayBridge.onResetSettings?.let { reset ->
                TextButton(
                    onClick = {
                        reset()
                        refreshKey++
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    Text("Reset All to Default", color = Color.Red.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
        }
    }
}
