package org.alpha3.launcher.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.alpha3.launcher.globals.editableExtensions

expect fun deleteFile(path: String): Boolean

@Composable
fun FileBrowser(startPath: String) {
    var currentPath by remember(startPath) { mutableStateOf(startPath) }
    var editingFilePath by remember { mutableStateOf<String?>(null) }

    // Use derivedStateOf to ensure files update when currentPath changes
    val files by remember(currentPath) {
        derivedStateOf { listFilesInDirectory(currentPath).sorted() }
    }

    if (editingFilePath != null) {
        TextEditor(
            path = editingFilePath!!,
            onDismiss = { editingFilePath = null }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // Header / Breadcrumb
            Text(
                text = "Current: /" + currentPath.substringAfterLast("Containers/Data/Application/").substringAfter("/"),
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Back button (if not root)
            if (currentPath != startPath && currentPath.length > startPath.length) {
                Text(
                    text = "⬅ .. (up)",
                    fontSize = 16.sp,
                    color = Color(0xFF0A84FF),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable {
                            val parent = currentPath.substringBeforeLast("/", "")
                            if (parent.isNotEmpty()) {
                                currentPath = parent
                            }
                        }
                        .padding(vertical = 12.dp)
                )
            }

            // File list
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(files) { fullPath ->
                    val name = fullPath.substringAfterLast("/")
                    val directory = isDirectory(fullPath)
                    var showActions by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (directory) {
                                    currentPath = fullPath
                                } else {
                                    showActions = !showActions
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (directory) "📁" else "📄",
                                fontSize = 18.sp,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = name,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }

                        if (!directory && showActions) {
                            Row(modifier = Modifier.padding(start = 32.dp, top = 4.dp)) {
                                val extension = name.substringAfterLast(".", "").lowercase()
                                if (extension in editableExtensions) {
                                    Text(
                                        text = "Edit",
                                        fontSize = 14.sp,
                                        color = Color(0xFF34C759),
                                        modifier = Modifier
                                            .clickable { editingFilePath = fullPath }
                                            .padding(end = 24.dp)
                                    )
                                }
                                
                                Text(
                                    text = "Delete",
                                    fontSize = 14.sp,
                                    color = Color(0xFFFF3B30),
                                    modifier = Modifier.clickable {
                                        if (deleteFile(fullPath)) {
                                            // Force refresh by slightly changing state
                                            currentPath = currentPath + ""
                                        }
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@Composable
fun TextEditor(path: String, onDismiss: () -> Unit) {
    var content by remember { mutableStateOf(readTextFile(path) ?: "") }
    var fontSize by remember { mutableStateOf(12) }
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(vertical = 32.dp, horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = Color.White, fontSize = 18.sp)
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (fontSize > 6) fontSize-- }) {
                            Text("A-", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Text(fontSize.toString(), color = Color.Gray, fontSize = 12.sp)
                        IconButton(onClick = { if (fontSize < 30) fontSize++ }) {
                            Text("A+", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            writeTextFile(path, content)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save")
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                // Editor Area with Line Numbers
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Line Numbers Gutter
                    val lines = content.split("\n")
                    val lineCount = lines.size.coerceAtLeast(1)
                    
                    Column(
                        modifier = Modifier
                            .width(40.dp)
                            .fillMaxHeight()
                            .background(Color.Black.copy(alpha = 0.2f))
                            .verticalScroll(verticalScrollState)
                            .padding(top = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        for (i in 1..lineCount) {
                            Text(
                                text = i.toString(),
                                style = TextStyle(
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.5).sp // Increased spacing for touch/readability
                                ),
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }

                    // Main Text Area with Horizontal Scrolling (Disabled Soft Wrap via container)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScrollState)
                            .verticalScroll(verticalScrollState)
                            .padding(8.dp)
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            modifier = Modifier.width(IntrinsicSize.Max).fillMaxHeight(),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.5).sp
                            ),
                            cursorBrush = SolidColor(Color(0xFF0A84FF))
                        )
                    }
                }
            }
        }
    }
}
