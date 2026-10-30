@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher.mods.downloader

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import okio.IOException
import org.alpha3.launcher.io.File
import org.alpha3.launcher.libarchive.AE_IFDIR
import org.alpha3.launcher.libarchive.ARCHIVE_EOF
import org.alpha3.launcher.libarchive.ARCHIVE_OK
import org.alpha3.launcher.libarchive.ARCHIVE_WARN
import cnames.structs.archive
import cnames.structs.archive_entry
import org.alpha3.launcher.libarchive.archive_entry_filetype
import org.alpha3.launcher.libarchive.archive_entry_is_encrypted
import org.alpha3.launcher.libarchive.archive_entry_pathname
import org.alpha3.launcher.libarchive.archive_entry_pathname_utf8
import org.alpha3.launcher.libarchive.archive_entry_size
import org.alpha3.launcher.libarchive.archive_error_string
import org.alpha3.launcher.libarchive.archive_format_name
import org.alpha3.launcher.libarchive.archive_read_data
import org.alpha3.launcher.libarchive.archive_read_data_skip
import org.alpha3.launcher.libarchive.archive_read_free
import org.alpha3.launcher.libarchive.archive_read_new
import org.alpha3.launcher.libarchive.archive_read_next_header
import org.alpha3.launcher.libarchive.archive_read_open_filename
import org.alpha3.launcher.libarchive.archive_read_support_filter_all
import org.alpha3.launcher.libarchive.archive_read_support_format_all
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCModeCBC
import platform.CoreCrypto.kCCModeECB
import platform.CoreCrypto.kCCModeCTR
import platform.CoreCrypto.kCCModeOptionCTR_BE
import platform.CoreCrypto.kCCSuccess
import platform.CoreCrypto.ccNoPadding
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.size_tVar

internal actual fun modDatabaseBuilder(): RoomDatabase.Builder<ModDatabase> {
    val dir = (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/Alpha3"
    File(dir).mkdirs()
    return Room.databaseBuilder<ModDatabase>(name = "$dir/mods_database.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}

internal actual fun logTimestamp(): String =
    NSDateFormatter().apply { dateFormat = "yyyy-MM-dd HH:mm:ss" }.stringFromDate(NSDate())

/** CommonCrypto's AES; CTR counts with the whole 128-bit block as a big-endian number, as MEGA needs. */
internal actual class AesCipher actual constructor(key: ByteArray, mode: AesMode, iv: ByteArray?, encrypt: Boolean) {
    private val ref = nativeHeap.alloc<CCCryptorRefVar>()

    init {
        val status = key.usePinned { k ->
            val ivPinned = iv?.copyOf()
            (ivPinned ?: ByteArray(16)).usePinned { v ->
                CCCryptorCreateWithMode(
                    if (encrypt) kCCEncrypt else kCCDecrypt,
                    when (mode) {
                        AesMode.ECB -> kCCModeECB
                        AesMode.CBC -> kCCModeCBC
                        AesMode.CTR -> kCCModeCTR
                    },
                    kCCAlgorithmAES,
                    ccNoPadding,
                    if (ivPinned != null) v.addressOf(0) else null,
                    k.addressOf(0),
                    key.size.toULong(),
                    null, 0u, 0,
                    if (mode == AesMode.CTR) kCCModeOptionCTR_BE else 0u,
                    ref.ptr,
                )
            }
        }
        if (status != kCCSuccess) {
            nativeHeap.free(ref)
            throw IllegalStateException("CommonCrypto could not set up AES ($status)")
        }
    }

    actual fun update(data: ByteArray, offset: Int, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val out = ByteArray(length)
        memScoped {
            val moved = alloc<size_tVar>()
            val status = data.usePinned { input ->
                out.usePinned { output ->
                    CCCryptorUpdate(ref.value, input.addressOf(offset), length.toULong(), output.addressOf(0), length.toULong(), moved.ptr)
                }
            }
            if (status != kCCSuccess) throw IllegalStateException("AES failed ($status)")
            return if (moved.value.toInt() == length) out else out.copyOf(moved.value.toInt())
        }
    }

    actual fun close() {
        CCCryptorRelease(ref.value)
        nativeHeap.free(ref)
    }
}

/** The system's libarchive (declared in src/nativeInterop/cinterop/libarchive.def). */
internal actual object Archives {
    private const val BLOCK_SIZE: ULong = 262_144uL // 256 KiB

    actual fun describe(): String = "libarchive (system)"

    private inline fun <T> open(path: String, block: (CPointer<archive>) -> T): T {
        val a = archive_read_new() ?: throw IOException("libarchive could not start")
        try {
            archive_read_support_filter_all(a)
            archive_read_support_format_all(a)
            if (archive_read_open_filename(a, path, BLOCK_SIZE) != ARCHIVE_OK) throw IOException(error(a, "Cannot open $path"))
            return block(a)
        } finally {
            archive_read_free(a)
        }
    }

    private fun error(a: CPointer<archive>, fallback: String) = archive_error_string(a)?.toKString() ?: fallback

    /** Calls [block] for every item, in order, with the archive positioned at its data. */
    private inline fun forEachItem(a: CPointer<archive>, block: (ArchiveFile) -> Unit) = memScoped {
        val entry = alloc<CPointerVar<archive_entry>>()
        while (true) {
            val result = archive_read_next_header(a, entry.ptr)
            if (result == ARCHIVE_EOF) break
            if (result < ARCHIVE_WARN) throw IOException(error(a, "Damaged archive"))
            val e = entry.value ?: continue
            val path = (archive_entry_pathname_utf8(e) ?: archive_entry_pathname(e))?.toKString() ?: continue
            block(
                ArchiveFile(
                    path = path.replace('\\', '/').trimEnd('/'),
                    isFolder = archive_entry_filetype(e).toInt() == AE_IFDIR,
                    size = archive_entry_size(e),
                    encrypted = archive_entry_is_encrypted(e) == 1,
                )
            )
        }
    }

    actual fun formatName(archive: String): String = open(archive) { a ->
        // The format is known once the first header is read
        memScoped {
            val entry = alloc<CPointerVar<archive_entry>>()
            archive_read_next_header(a, entry.ptr)
        }
        archive_format_name(a)?.toKString() ?: "unknown"
    }

    actual fun list(archive: String): List<ArchiveFile> = open(archive) { a ->
        val items = ArrayList<ArchiveFile>()
        forEachItem(a) { items += it; archive_read_data_skip(a) }
        items
    }

    actual fun extract(archive: String, target: (ArchiveFile) -> String?, onFile: (ArchiveFile) -> Unit, onError: (String) -> Unit) {
        open(archive) { a ->
            val buffer = ByteArray(256 * 1024)
            forEachItem(a) { item ->
                val path = target(item)
                if (path == null) {
                    archive_read_data_skip(a)
                    return@forEachItem
                }
                onFile(item)
                val file = fopen(path, "wb")
                if (file == null) {
                    onError("Cannot write $path")
                    archive_read_data_skip(a)
                    return@forEachItem
                }
                try {
                    buffer.usePinned { pinned ->
                        while (true) {
                            val n = archive_read_data(a, pinned.addressOf(0), buffer.size.toULong())
                            if (n == 0L) break
                            if (n < 0L) {
                                onError("${error(a, "read error")} for ${item.path}")
                                break
                            }
                            if (fwrite(pinned.addressOf(0), 1u, n.toULong(), file) != n.toULong()) {
                                onError("Cannot write $path")
                                break
                            }
                        }
                    }
                } finally {
                    fclose(file)
                }
            }
        }
    }
}

