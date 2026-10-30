package org.alpha3.launcher.mods.downloader

import androidx.room.Room
import androidx.room.RoomDatabase
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.alpha3.launcher.paths.OpenMWPathsAndroid
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual fun modDatabaseBuilder(): RoomDatabase.Builder<ModDatabase> =
    Room.databaseBuilder<ModDatabase>(OpenMWPathsAndroid.appContext, "mods_database.db")

internal actual fun logTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

internal actual class AesCipher actual constructor(key: ByteArray, mode: AesMode, iv: ByteArray?, encrypt: Boolean) {
    private val cipher = Cipher.getInstance("AES/${mode.name}/NoPadding").apply {
        val op = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (iv != null) init(op, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) else init(op, SecretKeySpec(key, "AES"))
    }

    actual fun update(data: ByteArray, offset: Int, length: Int): ByteArray = cipher.update(data, offset, length) ?: ByteArray(0)

    actual fun close() {}
}

internal actual object Archives {
    actual fun describe(): String {
        val version = SevenZip.getSevenZipVersion()
        return "7-Zip ${version.version}, 7-Zip-JBinding ${SevenZip.getSevenZipJBindingVersion()}"
    }

    private inline fun <T> open(archive: String, block: (IInArchive) -> T): T {
        SevenZip.getSevenZipVersion() // loads the native library
        RandomAccessFileInStream(RandomAccessFile(archive, "r")).use { stream ->
            val inArchive = SevenZip.openInArchive(null, stream) // the format is detected
            try {
                return block(inArchive)
            } finally {
                inArchive.close()
            }
        }
    }

    private fun item(inArchive: IInArchive, i: Int) = ArchiveFile(
        path = inArchive.getStringProperty(i, PropID.PATH).orEmpty().replace('\\', '/').trimEnd('/'),
        isFolder = inArchive.getProperty(i, PropID.IS_FOLDER) as? Boolean ?: false,
        size = inArchive.getProperty(i, PropID.SIZE) as? Long ?: 0L,
        encrypted = inArchive.getProperty(i, PropID.ENCRYPTED) as? Boolean ?: false,
    )

    actual fun formatName(archive: String): String = open(archive) { it.archiveFormat.methodName }

    actual fun list(archive: String): List<ArchiveFile> =
        open(archive) { inArchive -> (0 until inArchive.numberOfItems).map { item(inArchive, it) } }

    actual fun extract(archive: String, target: (ArchiveFile) -> String?, onFile: (ArchiveFile) -> Unit, onError: (String) -> Unit) {
        open(archive) { inArchive ->
            inArchive.extract(null, false, object : IArchiveExtractCallback {
                private var current: String? = null
                private var out: OutputStream? = null

                override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                    val item = item(inArchive, index)
                    val path = target(item) ?: return null
                    onFile(item)
                    current = path
                    out = java.io.File(path).outputStream().buffered()
                    return ISequentialOutStream { data -> out?.write(data); data.size }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode) {}

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    runCatching { out?.close() }
                    out = null
                    if (extractOperationResult != ExtractOperationResult.OK) onError("$extractOperationResult for $current")
                }

                override fun setTotal(total: Long) {}
                override fun setCompleted(complete: Long) {}
            })
        }
    }
}
