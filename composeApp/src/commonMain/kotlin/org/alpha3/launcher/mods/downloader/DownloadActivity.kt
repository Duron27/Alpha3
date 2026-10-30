package org.alpha3.launcher.mods.downloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.alpha3.launcher.ui.controls.UIStateManager.gold
import org.alpha3.launcher.utils.formatDecimals
import org.alpha3.launcher.mods.downloader.ModListManager.allowExtract

private enum class FileState { WAITING, DOWNLOADING, EXTRACTING, DONE, FAILED }

private class FileRow(
    val key: String,
    val name: String,
    val state: FileState,
    val percent: Int,
    val speedKb: Int,
    val detail: String,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/** "some-mod-slug" -> "Some Mod Slug", for a file whose real name is not known (yet). */
private fun prettySlug(slug: String) = slug.split('-', '_').filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

private fun nameOf(key: String): String {
    val slug = key.substringBefore('|')
    val base = ModListManager.modNames[slug] ?: prettySlug(slug)
    val file = key.substringAfter('|', "")
    return if (file.isEmpty()) base else "$base: $file"
}

/** One row per file, built from the progress maps the downloader and extractor already fill. */
private fun fileRows(): List<FileRow> {
    val keys = NexusInfo.downloadProgressMap.keys + NexusInfo.extractionProgressMap.keys
    return keys.map { key ->
        val dl = NexusInfo.downloadProgressMap[key].orEmpty()
        val ex = NexusInfo.extractionProgressMap[key]
        val name = nameOf(key)
        val dlPercent = NexusInfo.downloadProgressPercentMap[key] ?: 0
        val exPercent = NexusInfo.extractionProgressPercentMap[key]?.toInt() ?: 0
        val speed = NexusInfo.downloadSpeedMap[key] ?: 0
        when {
            // checked first: a mod called "... Error Marker ..." must not look like a failure
            ex != null && ex.endsWith("Extracted") -> FileRow(key, name, FileState.DONE, 100, 0, "")
            ex != null && (ex.contains("failed", ignoreCase = true) || ex.contains("error", ignoreCase = true)) ->
                FileRow(key, name, FileState.FAILED, 0, 0, ex)
            dl.contains("Failed") -> FileRow(key, name, FileState.FAILED, 0, 0, dl.substringAfter(": ", "Download failed"))
            ex != null && !ex.contains("Waiting") -> {
                val detail = ex.substringAfter(": ", "").ifBlank { "Unpacking" }
                FileRow(key, name, FileState.EXTRACTING, exPercent, 0, detail)
            }
            dl.contains("Downloaded") ->
                if (allowExtract) FileRow(key, name, FileState.EXTRACTING, 0, 0, "Waiting to unpack")
                else FileRow(key, name, FileState.DONE, 100, 0, "")
            dl.startsWith("Downloading") || dl.startsWith("Retrying") || dl.startsWith("Paused") ->
                FileRow(
                    key, name, FileState.DOWNLOADING, dlPercent, speed,
                    if (dl.startsWith("Paused")) "Paused" else if (dl.startsWith("Retrying")) "Retrying" else "",
                    NexusInfo.downloadBytesMap[key] ?: 0L, NexusInfo.downloadTotalMap[key] ?: 0L
                )
            else -> FileRow(key, name, FileState.WAITING, 0, 0, "")
        }
    }
}

/** Files that are done or failed, counted from what is on screen, so the number always agrees with the rows. */
fun finishedFileCount(): Int = fileRows().count { it.state == FileState.DONE || it.state == FileState.FAILED }

/** What the notification shows: a line per file being downloaded, how many are unpacking, and the bytes received so far. */
class NotificationProgress(val downloading: List<String>, val unpacking: Int, val receivedBytes: Long)

private const val NAME_CHARS = 24

fun notificationProgress(): NotificationProgress {
    val rows = fileRows()
    val downloading = rows.filter { it.state == FileState.DOWNLOADING }.sortedByDescending { it.percent }.map { row ->
        // Progress first and the name cut short: a notification line is one line, and what does not fit is dropped
        buildString {
            if (row.detail.isNotEmpty()) append(row.detail) else append(row.percent).append('%')
            if (row.totalBytes > 0) append(" of ").append(sizeText(row.totalBytes))
            if (row.speedKb > 0) append(" · ").append(speedText(row.speedKb))
            append("  ").append(if (row.name.length > NAME_CHARS) row.name.take(NAME_CHARS - 1).trimEnd() + "…" else row.name)
        }
    }
    return NotificationProgress(
        downloading, rows.count { it.state == FileState.EXTRACTING }, NexusInfo.downloadBytesMap.values.sum()
    )
}

fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${formatDecimals(bytes / (1024.0 * 1024 * 1024), 2)} GB"
    bytes >= 1L shl 20 -> "${formatDecimals(bytes / (1024.0 * 1024), 0)} MB"
    else -> "${bytes / 1024} KB"
}

private fun etaText(bytesLeft: Long, speedKb: Int): String? {
    if (speedKb <= 0 || bytesLeft <= 0) return null
    val s = bytesLeft / (speedKb * 1024L)
    return when {
        s < 90 -> "$s s"
        s < 90 * 60 -> "${(s + 30) / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

fun speedText(kb: Int) = if (kb >= 1024) "${formatDecimals(kb / 1024.0, 1)} MB/s" else "$kb KB/s"

/**
 * What the downloader is doing right now, in plain words: overall progress, the files that are downloading or being
 * unpacked (by name, with their own progress), anything that failed, and the finished ones folded away.
 */
@Composable
fun DownloadActivity() {
    val processing = StatusInfo.processing
    val rows = fileRows()
    if (rows.isEmpty() && !processing) return

    var showFinished by remember { mutableStateOf(false) }
    val waiting = rows.count { it.state == FileState.WAITING }
    val downloading = rows.filter { it.state == FileState.DOWNLOADING }
    val extracting = rows.filter { it.state == FileState.EXTRACTING }
    val done = rows.filter { it.state == FileState.DONE }
    val failed = rows.filter { it.state == FileState.FAILED }
    val totalSpeed = downloading.sumOf { it.speedKb }

    if (processing) {
        val total = ModListManager.runTotal.coerceAtLeast(1)
        val finished = (done.size + failed.size).coerceIn(0, total)
        Text(
            "Installing ${ModListManager.modList}",
            color = gold, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        LinearProgressIndicator(
            progress = { finished / total.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp)
        )
        Text(
            buildString {
                append("$finished of $total files finished")
                if (totalSpeed > 0) append(" · ${speedText(totalSpeed)}")
            },
            color = White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
        // Overall size from the sizes the list states; files whose size is unknown are left out of both numbers
        val sizes = ModListManager.fileSizeKb
        val knownTotal = rows.sumOf { (sizes[it.key] ?: 0L) * 1024 }
        if (knownTotal > 0) {
            val doneBytes = rows.sumOf {
                val size = (sizes[it.key] ?: 0L) * 1024
                when (it.state) {
                    FileState.DONE, FileState.EXTRACTING, FileState.FAILED -> if (it.state == FileState.FAILED) 0L else size
                    FileState.DOWNLOADING -> minOf(it.doneBytes, size.takeIf { s -> s > 0 } ?: it.doneBytes)
                    FileState.WAITING -> 0L
                }
            }
            val allKnown = ModListManager.fileSizeKb.values.sum() * 1024
            val eta = etaText((allKnown - doneBytes).coerceAtLeast(0), totalSpeed)
            Text(
                "${sizeText(doneBytes)} of ${sizeText(allKnown)}" + (eta?.let { " · about $it left" } ?: ""),
                color = White, fontSize = 12.sp
            )
        }
        Text(
            buildString {
                append("${downloading.size} downloading · ${extracting.size} unpacking · $waiting waiting")
                if (failed.isNotEmpty()) append(" · ${failed.size} failed")
                if (NxmDownloads.queuedCount > 0) append(" · ${NxmDownloads.queuedCount} waiting for the browser")
            },
            color = White.copy(alpha = 0.8f), fontSize = 11.sp
        )
        val inBrowser = NxmDownloads.currentTitle
        ModListManager.rateLimitWait?.let {
            Text(it, color = Color.Yellow, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        when {
            inBrowser != null -> Text(
                "In your browser: $inBrowser. Press Slow download on the Nexus page.",
                color = White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
            downloading.isEmpty() && extracting.isEmpty() && waiting > 0 -> Text(
                "Getting download links from Nexus. Downloads start as soon as each link is ready.",
                color = White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ModListManager.paused = !ModListManager.paused }) {
                Text(if (ModListManager.paused) "Resume downloads" else "Pause downloads", color = gold)
            }
            if (inBrowser != null) {
                TextButton(onClick = { NxmDownloads.skipCurrent() }) { Text("Skip this file", color = gold) }
            }
        }
    } else {
        Text(
            "Last run: ${done.size} finished" + if (failed.isNotEmpty()) " · ${failed.size} failed" else "",
            color = gold, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
        )
    }
    HorizontalDivider(color = White.copy(alpha = 0.4f), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

    val active = (downloading + extracting).sortedBy { it.name }
    active.take(MAX_ACTIVE_ROWS).forEach { ActiveRow(it) }
    if (active.size > MAX_ACTIVE_ROWS) {
        Text("+ ${active.size - MAX_ACTIVE_ROWS} more in progress", color = White.copy(alpha = 0.7f), fontSize = 11.sp)
    }

    if (failed.isNotEmpty()) {
        Text("Failed (${failed.size})", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        failed.sortedBy { it.name }.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(Icons.Default.Cancel, contentDescription = "Failed", tint = Color.Red, modifier = Modifier.padding(end = 6.dp))
                Column {
                    Text(row.name, color = White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (row.detail.isNotBlank()) Text(row.detail, color = Color.Red.copy(alpha = 0.9f), fontSize = 10.sp)
                }
            }
        }
    }

    if (done.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().clickable { showFinished = !showFinished }.padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Finished (${done.size})", color = Color.Green.copy(alpha = 0.8f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(if (showFinished) "Hide" else "Show", color = gold, fontSize = 12.sp)
        }
        if (showFinished) {
            done.sortedBy { it.name }.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Done", tint = Color.Green.copy(alpha = 0.7f), modifier = Modifier.padding(end = 6.dp))
                    Text(row.name, color = White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

private const val MAX_ACTIVE_ROWS = 12

@Composable
private fun ActiveRow(row: FileRow) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(row.name, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val line = when (row.state) {
            FileState.DOWNLOADING -> buildString {
                append(row.detail.ifEmpty { "Downloading" }).append(" ${row.percent}%")
                if (row.totalBytes > 0) append(" · ${sizeText(row.doneBytes)} of ${sizeText(row.totalBytes)}")
                if (row.speedKb > 0) append(" · ${speedText(row.speedKb)}")
                if (row.detail.isEmpty()) etaText(row.totalBytes - row.doneBytes, row.speedKb)?.let { append(" · $it left") }
            }
            else -> if (row.percent > 0) "Unpacking ${row.percent}%" else row.detail
        }
        Text(line, color = White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { row.percent.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(3.dp)
        )
    }
}
