package org.alpha3.launcher.mods.downloader

import androidx.compose.ui.graphics.Color
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.buffer
import okio.use
import org.alpha3.launcher.io.File
import org.alpha3.launcher.ui.view.addCustomLog
import org.alpha3.launcher.utils.Log
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "MegaDownloader"

class MegaDownloader {

    /** Returns the decrypted, verified file (named after the file's name on MEGA), or null if anything failed. */
    suspend fun download(megaUrl: String, targetDir: File): File? {
        var partialFile: File? = null
        Log.i(TAG, "Starting MEGA download: $megaUrl")
        addCustomLog("Starting MEGA download...", textSize = 10, textColor = Color.Cyan)

        try {
            // 1. Parse URL
            val (fileHandle, fileKeyB64) = parseMegaUrl(megaUrl)

            val fileKey = base64UrlDecode(fileKeyB64)
            if (fileKey.size != 32) throw Exception("Invalid key length: ${fileKey.size} bytes, expected 32")

            val keyInt = bytesToA32(fileKey)

            // k = [k[0] ^ k[4], k[1] ^ k[5], k[2] ^ k[6], k[3] ^ k[7]]
            val k = intArrayOf(
                keyInt[0] xor keyInt[4],
                keyInt[1] xor keyInt[5],
                keyInt[2] xor keyInt[6],
                keyInt[3] xor keyInt[7]
            )
            val iv = intArrayOf(keyInt[4], keyInt[5], 0, 0)
            val metaMac = intArrayOf(keyInt[6], keyInt[7])

            // 2. Get File Info and Download URL (one API call returns size, attributes and the URL)
            val fileInfo = getFileInfo(fileHandle)
            val fileSize = fileInfo["s"]?.jsonPrimitive?.longOrNull ?: throw Exception("MEGA returned no file size")
            val atB64 = fileInfo["at"]?.jsonPrimitive?.contentOrNull ?: throw Exception("MEGA returned no attributes")

            // The actual download URL, always over HTTPS
            var downloadUrl = fileInfo["g"]!!.jsonPrimitive.content
            if (downloadUrl.startsWith("http://")) {
                downloadUrl = downloadUrl.replace("http://", "https://")
                Log.d(TAG, "Converted to HTTPS: $downloadUrl")
            }

            // 3. Decrypt Filename
            // Only the last path segment: the (untrusted) name might carry a path, like "../x"
            val realFileName = decryptAttributes(atB64, k)?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.takeIf { it.isNotBlank() && it != ".." && it != "." }
                ?: "mega_download_${fileHandle}.bin"

            // Ensure target is a directory and exists
            val workingDir = if (targetDir.isFile) targetDir.parentFile ?: targetDir else targetDir
            if (!workingDir.exists()) workingDir.mkdirs()

            val finalDest = File(workingDir, realFileName)
            partialFile = finalDest

            Log.i(TAG, "File identified: $realFileName ($fileSize bytes)")
            addCustomLog("MEGA: $realFileName (${fileSize / 1024} KB)", textSize = 10, textColor = Color.White)

            // 4. Download and Decrypt on the fly
            val success = downloadAndDecrypt(downloadUrl, finalDest, k, iv, metaMac, fileSize)

            if (!success) throw Exception("Integrity verification failed (MAC mismatch)")
            Log.i(TAG, "SUCCESS! File verified: ${finalDest.name}")
            addCustomLog("MEGA SUCCESS: ${finalDest.name}", textSize = 10, textColor = Color.Green)
            return finalDest
        } catch (e: kotlinx.coroutines.CancellationException) {
            partialFile?.let { if (it.exists()) it.delete() }
            throw e
        } catch (e: Exception) {
            // Never leave a half-written or unverified file behind
            partialFile?.let { if (it.exists()) it.delete() }
            Log.e(TAG, "Download failed: ${e.message}", e)
            addCustomLog("MEGA Error: ${e.message}", textSize = 10, textColor = Color.Red)
            return null
        }
    }

    private fun parseMegaUrl(url: String): Pair<String, String> {
        return when {
            url.contains("!") -> {
                val parts = url.split("!")
                if (parts.size < 3) throw Exception("Invalid legacy MEGA URL")
                parts[1] to parts[2]
            }
            url.contains("#") -> {
                val base = url.substringBefore("#")
                val key = url.substringAfter("#")
                val handle = base.substringAfterLast("/")
                if (handle.isEmpty() || key.isEmpty()) throw Exception("Invalid modern MEGA URL")
                handle to key
            }
            else -> throw Exception("Unsupported MEGA URL format")
        }
    }

    private suspend fun getFileInfo(handle: String): JsonObject {
        val response = ModHttp.client.post("https://g.api.mega.co.nz/cs") {
            contentType(ContentType.Application.Json)
            setBody("[{\"a\":\"g\",\"g\":1,\"p\":\"$handle\"}]")
        }
        if (!response.status.isSuccess()) throw Exception("MEGA API HTTP error ${response.status.value}")
        val bodyString = response.bodyAsText()
        Log.d(TAG, "API Response: ${bodyString.take(200)}...")
        // A bare number (like [-9]) is an error code, e.g. -9 = file not found
        val first = (ModListManager.json.parseToJsonElement(bodyString) as? JsonArray)?.firstOrNull()
            ?: throw Exception("MEGA API returned nothing")
        if (first is JsonPrimitive) throw Exception("MEGA API error code: ${first.intOrNull ?: first.content}")
        val obj = first.jsonObject
        if ("e" in obj) throw Exception("MEGA API error: ${obj["e"]}")
        if ("g" !in obj) throw Exception("MEGA returned no download URL (is this a file link?)")
        return obj
    }

    private fun decryptAttributes(atB64: String, k: IntArray): String? {
        try {
            // Attributes are AES-CBC with a zero IV: "MEGA{...json...}" padded with zeros
            val at = base64UrlDecode(atB64)
            val cipher = AesCipher(a32ToBytes(k), AesMode.CBC, ByteArray(16), encrypt = false)
            val decrypted = try { cipher.update(at, 0, at.size - at.size % 16) } finally { cipher.close() }

            val str = decrypted.decodeToString().trimEnd('\u0000')
            if (str.startsWith("MEGA")) {
                return ModListManager.json.parseToJsonElement(str.substring(4)).jsonObject["n"]
                    ?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt attributes", e)
        }
        return null
    }

    private suspend fun downloadAndDecrypt(
        downloadUrl: String,
        finalDest: File,
        k: IntArray,
        iv: IntArray,
        expectedMetaMac: IntArray,
        fileSize: Long
    ): Boolean {
        val aesKey = a32ToBytes(k)

        // The file is AES-CTR. The 128-bit counter starts at [iv0, iv1, 0, 0] and counts up as a
        // big-endian number, so the low word is the LAST one, and the cipher handles block alignment.
        val ctr = AesCipher(aesKey, AesMode.CTR, a32ToBytes(intArrayOf(iv[0], iv[1], 0, 0)), encrypt = false)
        val mac = MegaMac(aesKey, iv)
        Log.d(TAG, "Downloading from: $downloadUrl")

        try {
            ModHttp.client.prepareGet(downloadUrl) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                header("Accept", "*/*")
                header("Accept-Encoding", "identity")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    Log.e(TAG, "Download failed with code ${response.status.value}: ${response.bodyAsText().take(300)}")
                    throw Exception("Download failed with code ${response.status.value}")
                }
                Log.d(TAG, "Content-Length: ${response.headers["Content-Length"]}, Expected: $fileSize")

                // The MAC runs over the decrypted data in chunks of 128KB, 256KB, ... up to 1MB each.
                var chunkSize = 0x20000L
                var chunkEnd = minOf(chunkSize, fileSize)
                var total = 0L
                var lastLogged = 0L

                val buffer = ByteArray(65536)
                val input = response.bodyAsChannel()
                finalDest.sink().buffer().use { out ->
                    while (true) {
                        val n = input.readAvailable(buffer, 0, buffer.size)
                        if (n == -1) break
                        if (n == 0) continue
                        if (total + n > fileSize) throw Exception("MEGA sent more data than the file size")

                        val plain = ctr.update(buffer, 0, n)
                        var off = 0
                        while (off < n) {
                            val take = minOf((n - off).toLong(), chunkEnd - total).toInt()
                            mac.feed(plain, off, take)
                            off += take
                            total += take
                            if (total == chunkEnd) {
                                mac.endChunk()
                                if (chunkSize < 0x100000L) chunkSize += 0x20000L
                                chunkEnd = minOf(chunkEnd + chunkSize, fileSize)
                            }
                        }
                        out.write(plain)

                        if (total - lastLogged >= 1024 * 1024) {
                            lastLogged = total
                            Log.d(TAG, "Downloaded: ${total / 1024} KB / ${fileSize / 1024} KB")
                        }
                    }
                }
                if (total != fileSize) throw Exception("Download incomplete: got $total of $fileSize bytes")
            }
        } finally {
            ctr.close()
        }

        val computed = mac.metaMac()
        mac.close()
        val matches = computed[0] == expectedMetaMac[0] && computed[1] == expectedMetaMac[1]
        Log.d(TAG, "MAC ${if (matches) "PASSED" else "FAILED"}: expected [${expectedMetaMac[0]}, ${expectedMetaMac[1]}], got [${computed[0]}, ${computed[1]}]")
        return matches
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlDecode(s: String): ByteArray {
        var str = s.replace('-', '+').replace('_', '/')
        while (str.length % 4 != 0) str += "="
        return Base64.Default.decode(str)
    }
}

/**
 * MEGA's file MAC. Each chunk is CBC-MACed on its own (IV = [iv0, iv1, iv0, iv1], last partial block
 * zero-padded), and the chunk MACs are folded together with fileMac = AES(fileMac xor chunkMac).
 */
private class MegaMac(private val key: ByteArray, iv: IntArray) {
    private val ecb = AesCipher(key, AesMode.ECB, null, encrypt = true)
    private val chunkIv = a32ToBytes(intArrayOf(iv[0], iv[1], iv[0], iv[1]))
    private var fileMac = ByteArray(16)
    private val lastBlock = ByteArray(16)
    private val pending = ByteArray(16)
    private var pendingLen = 0
    private var cbc: AesCipher? = null

    /** [data] must lie inside the current chunk; chunk sizes are multiples of 16, except the file's last. */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var o = offset
        var l = length
        if (pendingLen > 0) {
            val take = minOf(16 - pendingLen, l)
            data.copyInto(pending, pendingLen, o, o + take)
            pendingLen += take
            o += take
            l -= take
            if (pendingLen == 16) {
                absorb(pending, 0, 16)
                pendingLen = 0
            }
        }
        val aligned = l - l % 16
        if (aligned > 0) {
            absorb(data, o, aligned)
            o += aligned
            l -= aligned
        }
        if (l > 0) {
            data.copyInto(pending, 0, o, o + l)
            pendingLen = l
        }
    }

    fun endChunk() {
        if (pendingLen > 0) {
            pending.fill(0, pendingLen, 16)
            absorb(pending, 0, 16)
            pendingLen = 0
        }
        for (i in 0 until 16) fileMac[i] = (fileMac[i].toInt() xor lastBlock[i].toInt()).toByte()
        fileMac = ecb.update(fileMac, 0, 16)
        cbc?.close()
        cbc = null
    }

    fun metaMac(): IntArray {
        val m = bytesToA32(fileMac)
        return intArrayOf(m[0] xor m[1], m[2] xor m[3])
    }

    fun close() {
        cbc?.close()
        ecb.close()
    }

    private fun absorb(data: ByteArray, offset: Int, length: Int) {
        val c = cbc ?: AesCipher(key, AesMode.CBC, chunkIv, encrypt = true).also { cbc = it }
        val out = c.update(data, offset, length)
        out.copyInto(lastBlock, 0, out.size - 16, out.size)
    }
}

private fun bytesToA32(b: ByteArray): IntArray {
    val result = IntArray(b.size / 4)
    for (i in result.indices) {
        result[i] = ((b[i * 4].toInt() and 0xFF) shl 24) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 16) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 8) or
                (b[i * 4 + 3].toInt() and 0xFF)
    }
    return result
}

private fun a32ToBytes(a: IntArray): ByteArray {
    val b = ByteArray(a.size * 4)
    for (i in a.indices) {
        val v = a[i]
        b[i * 4] = (v shr 24).toByte()
        b[i * 4 + 1] = (v shr 16).toByte()
        b[i * 4 + 2] = (v shr 8).toByte()
        b[i * 4 + 3] = v.toByte()
    }
    return b
}
