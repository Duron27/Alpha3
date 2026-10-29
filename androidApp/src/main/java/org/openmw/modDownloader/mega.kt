package org.openmw.modDownloader

import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.Color
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.alpha3.launcher.ui.view.addCustomLog
import java.io.File
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "MegaDownloader"

class MegaDownloader {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Returns the decrypted, verified file (named after the file's name on MEGA), or null if anything failed. */
    fun download(megaUrl: String, targetDir: File): File? {
        var partialFile: File? = null
        Log.i(TAG, "Starting MEGA download: $megaUrl")
        addCustomLog("Starting MEGA download...", textSize = 10, textColor = Color.Cyan)

        try {
            // 1. Parse URL
            val parts = parseMegaUrl(megaUrl)
            val fileHandle = parts[0]
            val fileKeyB64 = parts[1]

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
            val fileSize = fileInfo.getLong("s")
            val atB64 = fileInfo.getString("at")

            // Get the actual download URL and ensure it's HTTPS
            var downloadUrl = fileInfo.getString("g")

            // Force HTTPS
            if (downloadUrl.startsWith("http://")) {
                downloadUrl = downloadUrl.replace("http://", "https://")
                Log.d(TAG, "Converted to HTTPS: $downloadUrl")
            }

            // 3. Decrypt Filename
            // File(...).name drops any path the (untrusted) name might carry, like "../x"
            val realFileName = decryptAttributes(atB64, k)?.let { File(it).name }?.takeIf { it.isNotBlank() }
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
        } catch (e: Exception) {
            // Never leave a half-written or unverified file behind
            partialFile?.let { if (it.exists()) it.delete() }
            Log.e(TAG, "Download failed: ${e.message}", e)
            addCustomLog("MEGA Error: ${e.message}", textSize = 10, textColor = Color.Red)
            return null
        }
    }

    private fun parseMegaUrl(url: String): Array<String> {
        return when {
            url.contains("!") -> {
                val parts = url.split("!")
                if (parts.size < 3) throw Exception("Invalid legacy MEGA URL")
                arrayOf(parts[1], parts[2])
            }
            url.contains("#") -> {
                val base = url.substringBefore("#")
                val key = url.substringAfter("#")
                val handle = base.substringAfterLast("/")
                if (handle.isEmpty() || key.isEmpty()) throw Exception("Invalid modern MEGA URL")
                arrayOf(handle, key)
            }
            else -> throw Exception("Unsupported MEGA URL format")
        }
    }

    private fun getFileInfo(handle: String): JSONObject {
        val payload = "[{\"a\":\"g\",\"g\":1,\"p\":\"$handle\"}]"
        val req = Request.Builder()
            .url("https://g.api.mega.co.nz/cs")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw Exception("MEGA API HTTP error ${response.code}")
            val bodyString = response.body.string()
            Log.d(TAG, "API Response: ${bodyString.take(200)}...")
            // A bare number (like [-9]) is an error code, e.g. -9 = file not found
            val first = JSONArray(bodyString).get(0)
            if (first is Int) throw Exception("MEGA API error code: $first")
            val obj = first as JSONObject
            if (obj.has("e")) throw Exception("MEGA API error: ${obj.get("e")}")
            if (!obj.has("g")) throw Exception("MEGA returned no download URL (is this a file link?)")
            return obj
        }
    }

    private fun decryptAttributes(atB64: String, k: IntArray): String? {
        try {
            // Attributes are AES-CBC with a zero IV: "MEGA{...json...}" padded with zeros
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(a32ToBytes(k), "AES"), IvParameterSpec(ByteArray(16)))
            val at = base64UrlDecode(atB64)
            val decrypted = cipher.doFinal(at, 0, at.size - at.size % 16)

            val str = String(decrypted, Charsets.UTF_8).trimEnd('\u0000')
            if (str.startsWith("MEGA")) {
                return JSONObject(str.substring(4)).optString("n").ifEmpty { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt attributes", e)
        }
        return null
    }

    private fun downloadAndDecrypt(
        downloadUrl: String,
        finalDest: File,
        k: IntArray,
        iv: IntArray,
        expectedMetaMac: IntArray,
        fileSize: Long
    ): Boolean {
        val aesKey = SecretKeySpec(a32ToBytes(k), "AES")

        // The file is AES-CTR. The 128-bit counter starts at [iv0, iv1, 0, 0] and counts up as a
        // big-endian number, so the low word is the LAST one, and the cipher handles block alignment.
        val ctr = Cipher.getInstance("AES/CTR/NoPadding")
        ctr.init(Cipher.DECRYPT_MODE, aesKey, IvParameterSpec(a32ToBytes(intArrayOf(iv[0], iv[1], 0, 0))))
        val mac = MegaMac(aesKey, iv)

        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .build()

        Log.d(TAG, "Downloading from: $downloadUrl")

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                Log.e(TAG, "Download failed with code ${response.code}: $errorBody")
                throw Exception("Download failed with code ${response.code}")
            }
            Log.d(TAG, "Content-Length: ${response.header("Content-Length")}, Expected: $fileSize")

            // The MAC runs over the decrypted data in chunks of 128KB, 256KB, ... up to 1MB each.
            var chunkSize = 0x20000L
            var chunkEnd = minOf(chunkSize, fileSize)
            var total = 0L
            var lastLogged = 0L

            val buffer = ByteArray(65536)
            FileOutputStream(finalDest).use { out ->
                val input = response.body.byteStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
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

        val computed = mac.metaMac()
        val matches = computed[0] == expectedMetaMac[0] && computed[1] == expectedMetaMac[1]
        Log.d(TAG, "MAC ${if (matches) "PASSED" else "FAILED"}: expected [${expectedMetaMac[0]}, ${expectedMetaMac[1]}], got [${computed[0]}, ${computed[1]}]")
        return matches
    }

    private fun base64UrlDecode(s: String): ByteArray {
        var str = s.replace('-', '+').replace('_', '/')
        while (str.length % 4 != 0) str += "="
        return Base64.decode(str, Base64.DEFAULT)
    }
}

/**
 * MEGA's file MAC. Each chunk is CBC-MACed on its own (IV = [iv0, iv1, iv0, iv1], last partial block
 * zero-padded), and the chunk MACs are folded together with fileMac = AES(fileMac xor chunkMac).
 */
private class MegaMac(private val key: SecretKeySpec, iv: IntArray) {
    private val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    private val chunkIv = IvParameterSpec(a32ToBytes(intArrayOf(iv[0], iv[1], iv[0], iv[1])))
    private var fileMac = ByteArray(16)
    private val lastBlock = ByteArray(16)
    private val pending = ByteArray(16)
    private var pendingLen = 0
    private var cbc: Cipher? = null

    /** [data] must lie inside the current chunk; chunk sizes are multiples of 16, except the file's last. */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var o = offset
        var l = length
        if (pendingLen > 0) {
            val take = minOf(16 - pendingLen, l)
            System.arraycopy(data, o, pending, pendingLen, take)
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
            System.arraycopy(data, o, pending, 0, l)
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
        fileMac = ecb.doFinal(fileMac)
        cbc = null
    }

    fun metaMac(): IntArray {
        val m = bytesToA32(fileMac)
        return intArrayOf(m[0] xor m[1], m[2] xor m[3])
    }

    private fun absorb(data: ByteArray, offset: Int, length: Int) {
        val c = cbc ?: Cipher.getInstance("AES/CBC/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, key, chunkIv)
            cbc = it
        }
        val out = c.update(data, offset, length)
        System.arraycopy(out, out.size - 16, lastBlock, 0, 16)
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
