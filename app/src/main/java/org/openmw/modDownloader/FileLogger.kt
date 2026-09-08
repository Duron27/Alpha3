package org.openmw.modDownloader

import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openmw.Constants
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object FileLogger {
    private const val LOG_FILE_NAME = "mod_downloader_debug.log"
    private val lock = ReentrantLock()
    private val logBuffer = StringBuilder()

    private fun getLogFile(): File {
        val logDir = File("${Constants.USER_FILE_STORAGE}/OpenMW")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        return File(logDir, LOG_FILE_NAME)
    }

    fun clearLog() {
        lock.withLock {
            try {
                val logFile = getLogFile()
                if (logFile.exists()) {
                    logFile.delete()
                }
                logBuffer.clear()
            } catch (e: Exception) {
               Log.e("FileLogger", "Failed to clear log file", e)
            }
        }
    }

    fun getLogContents(): String? {
        lock.withLock {
            return try {
                val logFile = getLogFile()
                if (logFile.exists()) {
                    logFile.readText()
                } else {
                    "No log file found"
                }
            } catch (e: Exception) {
                Log.e("FileLogger", "Failed to read log file", e)
                "Error reading log file: ${e.message}"
            }
        }
    }
}

@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    var logContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isLoading = true
        logContent = withContext(Dispatchers.IO) {
            FileLogger.getLogContents()
        }
        isLoading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(600.dp)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Debug Logs", style = MaterialTheme.typography.headlineSmall)
                    Row {
                        Button(
                            onClick = {
                                // Clear logs
                                FileLogger.clearLog()
                                logContent = "Logs cleared"
                            },
                        ) {
                            Text("Clear")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = logContent ?: "No log content",
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.1f))
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}