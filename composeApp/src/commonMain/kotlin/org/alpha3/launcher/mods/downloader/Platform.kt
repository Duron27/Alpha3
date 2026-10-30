package org.alpha3.launcher.mods.downloader

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.alpha3.launcher.utils.Platform

/**
 * What the mod downloader needs from the host platform: the browser for free Nexus members, keeping the app working
 * while a list installs, and opening links. A platform that provides one ([org.alpha3.launcher.utils.PlatformFeatures.modDownloaderHost])
 * gets the downloader tab.
 */
interface ModDownloaderHost {
    /**
     * Shows a free member's Nexus file page. The nxm:// link the page leads to goes to [NxmDownloads.handle]; a page
     * closed without one goes to [NxmDownloads.skipCurrent].
     */
    fun openNexusPage(url: String)

    fun openSignIn(url: String)

    /** Closes the Nexus pages the app itself shows (the run was cancelled); a browser tab is left to the person. */
    fun closeNexusPages() {}

    /** Called when a run starts and ends; keeps the app working meanwhile (a foreground service, no idle sleep). */
    fun setRunning(running: Boolean) {}

    /** A link in a mod's details was tapped; [fileName] is the archive's name for a direct download link. */
    fun openLink(url: String, label: String, fileName: String?)
}

internal val host: ModDownloaderHost? get() = Platform.features.modDownloaderHost

/**
 * A lock for the few lines of bookkeeping that downloads and extractions update at once (`synchronized` is JVM-only).
 * It spins, so the code it guards must be short and must not take it again.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SpinLock {
    @PublishedApi internal val held = AtomicInt(0)

    inline fun <T> withLock(block: () -> T): T {
        while (!held.compareAndSet(0, 1)) { /* held for a few instructions at most */ }
        try {
            return block()
        } finally {
            held.store(0)
        }
    }
}

/** Local time as "yyyy-MM-dd HH:mm:ss", for the download and extraction logs. */
internal expect fun logTimestamp(): String

internal enum class AesMode { ECB, CBC, CTR }

/** AES without padding, for MEGA's files: [update] takes whole blocks, except in CTR mode. */
internal expect class AesCipher(key: ByteArray, mode: AesMode, iv: ByteArray?, encrypt: Boolean) {
    fun update(data: ByteArray, offset: Int, length: Int): ByteArray
    fun close()
}

/** An item of an archive; [path] uses '/' and has no trailing one. */
data class ArchiveFile(
    val path: String,
    val isFolder: Boolean,
    val size: Long,
    val encrypted: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "")
}

/** Reads 7z, RAR and ZIP archives: 7-Zip-JBinding on Android, the system's libarchive on iOS. */
internal expect object Archives {
    /** Library and version, for the log. */
    fun describe(): String

    /** The archive's format, detected from its contents. Throws when the file is not an archive it can read. */
    fun formatName(archive: String): String

    /** Every item, in archive order. Throws when the file is not an archive it can read. */
    fun list(archive: String): List<ArchiveFile>

    /**
     * Writes every file of [archive] to the path [target] gives it (null skips it; its folder exists). [onFile] comes
     * before each file is written and [onError] after one that failed. Throws when the archive cannot be read at all.
     */
    fun extract(archive: String, target: (ArchiveFile) -> String?, onFile: (ArchiveFile) -> Unit, onError: (String) -> Unit)
}
