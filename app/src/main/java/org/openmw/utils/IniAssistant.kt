package org.openmw.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.openmw.Constants
import org.openmw.ui.controls.UIStateManager.darkGray
import java.io.File

private fun readIniValues(): Map<String, List<Triple<String, Any, String?>>> {
    val settings = mutableMapOf<String, MutableList<Triple<String, Any, String?>>>()
    val comments = mutableMapOf<String, String?>()
    val sections = mutableMapOf<String, MutableMap<String, String>>()
    var currentSection: String? = null

    // Define your blacklist here
    val blacklist = setOf("key1", "key2", "key3")
    val file = File(Constants.SETTINGS_FILE)
    if (!file.exists()) return emptyMap()

    val lines = file.readLines()

    for (i in lines.indices) {
        val line = lines[i].trim()
        when {
            line.startsWith("[") && line.endsWith("]") -> {
                currentSection = line.substring(1, line.length - 1).trim()
                sections[currentSection!!] = mutableMapOf()
            }
            "=" in line -> {
                val parts = line.split("=", limit = 2).map { it.trim() }
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = parts[1]
                    if (currentSection != null && key !in blacklist) {
                        sections[currentSection]!![key] = value

                        // Expect comment on TOP (above the key)
                        var j = i - 1
                        val commentLines = mutableListOf<String>()
                        while (j >= 0) {
                            val prevLine = lines[j].trim()
                            if (prevLine.startsWith("#")) {
                                commentLines.add(0, prevLine.substring(1).trim())
                                j--
                            } else if (prevLine.isEmpty()) {
                                // Allow empty lines between comment and key
                                j--
                            } else {
                                break
                            }
                        }
                        if (commentLines.isNotEmpty()) {
                            comments[key] = commentLines.joinToString("\n")
                        }
                    }
                }
            }
        }
    }

    sections.forEach { (section, properties) ->
        val sectionSettings = properties.mapNotNull { (key, value) ->
            if (key in blacklist) return@mapNotNull null
            val parsedValue: Any = when {
                value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> value.toBoolean()
                value.toIntOrNull() != null -> value.toInt()
                value.toFloatOrNull() != null -> value.toFloat()
                else -> value
            }
            Triple(key, parsedValue, comments[key])
        }
        settings[section] = sectionSettings.toMutableList()
    }
    return settings
}


fun writeIniValue(section: String, key: String, value: Any, comment: String?) {
    val settingsFile = File(Constants.SETTINGS_FILE)
    val lines = settingsFile.readLines().toMutableList()
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
    settingsFile.writeText(lines.joinToString("\n"))
}

@Composable
fun IniSettings() {
    var settings by remember { mutableStateOf(readIniValues()) }
    var searchQuery by remember { mutableStateOf("") }
    val view = LocalView.current
    val context = LocalContext.current
    val translationChecked by GameFilesPreferences.getPreference(context, GameFilesPreferences.TRANSLATION_ENABLED_KEY, false)
        .collectAsState(initial = false)

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
                IniSectionCard(
                    section = section,
                    sectionSettings = sectionSettings,
                    translationChecked = translationChecked,
                    onValueChange = { key, newValue ->
                        writeIniValue(section, key, newValue, null)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
                        }
                        settings = readIniValues()
                    }
                )
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
                    sectionSettings.forEachIndexed { index, (key, value, comment) ->
                        IniSettingItem(
                            propertyKey = key,
                            value = value,
                            comment = comment,
                            translationChecked = translationChecked,
                            onValueChange = { onValueChange(key, it) }
                        )
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
    val context = LocalContext.current
    val extractedKey = propertyKey.substringAfterLast('.')
    
    var translatedKey by remember { mutableStateOf(extractedKey) }
    var translatedComment by remember { mutableStateOf(comment ?: "") }

    if (translationChecked) {
        TranslateText(context, extractedKey) { translatedKey = it }
        if (comment != null) {
            TranslateText(context, comment) { translatedComment = it }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = translatedKey,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (comment != null) {
                    Text(
                        text = translatedComment,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

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
                is Int, is Float -> {
                    var textValue by remember(value) { mutableStateOf(value.toString()) }
                    val focusManager = LocalFocusManager.current
                    
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { 
                            textValue = it
                            if (value is Int) {
                                it.toIntOrNull()?.let { onValueChange(it) }
                            } else {
                                it.toFloatOrNull()?.let { onValueChange(it) }
                            }
                        },
                        modifier = Modifier.width(100.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = if (value is Int) KeyboardType.Number 
                                          else KeyboardType.Decimal
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
                else -> {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Cyan
                    )
                }
            }
        }
    }
}
