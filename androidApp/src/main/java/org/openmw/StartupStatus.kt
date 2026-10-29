package org.openmw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import java.io.File

/**
 * The engine shows nothing until it is far enough along to draw its own loading screen, and with a long mod list the
 * first part (opening the archives, listing the data folders) takes a minute with a black screen. This reads what the
 * engine has written to its log and says what it is doing, until its own loading screen takes over.
 */
@Composable
fun StartupStatus(startedAt: Long) {
    var message by remember { mutableStateOf<String?>("Starting OpenMW…") }
    var progress by remember { mutableStateOf(0f) }
    val prefs = LocalContext.current.getSharedPreferences("startup_status", 0)

    LaunchedEffect(Unit) {
        // The engine cannot tell how far along it is here, so the bar runs against how long the last start took
        val expected = prefs.getLong("expected_ms", 60_000L).coerceAtLeast(5_000L)
        val log = File(OpenMWPaths.ACTIVE_CONFIG, "openmw.log")
        var archives = false
        while (message != null) {
            val text = withContext(Dispatchers.IO) {
                // an old log (a previous run) says nothing about this one
                if (log.exists() && log.lastModified() >= startedAt - 5000) runCatching { log.readText() }.getOrNull() else null
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val seconds = elapsed / 1000
            progress = (elapsed.toFloat() / expected).coerceAtMost(0.97f)
            when {
                text == null -> {}
                text.contains("Loading content file") -> {
                    // only a start that waited for this phase says how long it takes
                    if (archives) prefs.edit().putLong("expected_ms", elapsed).apply()
                    message = null
                }
                text.contains("Adding BSA archive") -> {
                    archives = true
                    message = "Reading game files… (${seconds}s)"
                }
                archives -> {}
                else -> message = "Starting OpenMW… (${seconds}s)"
            }
            if (message != null) delay(500)
        }
    }

    message?.let {
        val gold = Color(0xFFDFD0A0)
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = gold, fontSize = 20.sp, textAlign = TextAlign.Center)
                Box(Modifier.padding(top = 16.dp).fillMaxWidth(0.5f).height(14.dp).border(1.dp, gold).padding(2.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color(0xFF9A7B3C)))
                }
            }
        }
    }
}
