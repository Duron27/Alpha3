package org.alpha3.launcher.crash

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

actual fun writeZip(destinationPath: String, entries: List<ZipSource>): Boolean = try {
    File(destinationPath).parentFile?.mkdirs()
    ZipOutputStream(FileOutputStream(destinationPath).buffered()).use { zip ->
        for (entry in entries) {
            zip.putNextEntry(ZipEntry(entry.name))
            zip.write(entry.bytes)
            zip.closeEntry()
        }
    }
    true
} catch (e: Exception) {
    false
}

actual fun fileTimestamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
