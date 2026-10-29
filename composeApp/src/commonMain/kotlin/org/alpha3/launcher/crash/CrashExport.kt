package org.alpha3.launcher.crash

import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.PlatformContext

object CrashExport {
    /** Largest part of openmw.log that goes in; the end of it is what matters. */
    private const val LOG_BYTES = 8L * 1024 * 1024

    /** Files of the config folder that explain a crash. */
    private val configFiles = listOf(
        "crash_report.txt", "tombstone_last.txt", "tombstone_last.pb", "crash.log", "native_crash.log", "renderer.status",
        "settings.cfg", "openmw.cfg", "shaders.yaml",
    )

    /**
     * Zips the previous crash into the Alpha3 folder as Alpha3-crash-<date>.zip: the symbolized report as it is shown,
     * then every file it was made from. Returns the path, or null when nothing could be written.
     */
    fun export(context: PlatformContext, data: CrashData): String? {
        val entries = ArrayList<ZipSource>()
        entries += ZipSource("report.txt", CrashLoader.toText(data).encodeToByteArray())
        for (name in configFiles) {
            val file = File(OpenMWPaths.ACTIVE_CONFIG, name)
            if (file.exists() && file.length() > 0) {
                runCatching { entries += ZipSource(name, file.readBytes()) }
            }
        }
        val log = File(OpenMWPaths.OPENMW_LOG)
        if (log.exists()) {
            runCatching { entries += ZipSource("openmw.log", log.readTailBytes(LOG_BYTES)) }
        }
        val destination = "${OpenMWPaths.USER_FILE_STORAGE}/Alpha3-crash-${fileTimestamp()}.zip"
        return destination.takeIf { writeZip(it, entries) }
    }
}
