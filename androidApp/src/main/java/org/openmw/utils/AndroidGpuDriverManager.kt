package org.openmw.utils

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.GpuDriverManager
import org.alpha3.launcher.utils.GpuFamily
import org.alpha3.launcher.utils.GpuDrivers
import org.alpha3.launcher.utils.InstalledGpuDriver
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Custom Vulkan drivers, each in its own folder holding the library, its `meta.json` and `source.txt`.
 *
 * Two copies are kept:
 *  - the **store** (`<Alpha3 storage>/gpu_drivers/<id>/`) next to the app's settings. It survives an uninstall,
 *    so a driver that was downloaded is still there after a reinstall. This is the list the user sees.
 *  - the **working copy** (`files/gpu_drivers/<id>/`) in the app's private storage. libadrenotools only loads
 *    drivers from there. It is (re)made from the store whenever it is missing.
 */
class AndroidGpuDriverManager(private val context: Context) : GpuDriverManager {

    override val deviceApi: Int = Build.VERSION.SDK_INT

    override val gpuFamily: GpuFamily by lazy { detectGpuFamily() }

    override suspend fun installed(): List<InstalledGpuDriver> = withContext(Dispatchers.IO) {
        installedBlocking(context)
    }

    override suspend fun install(url: String, onProgress: (Float) -> Unit): InstalledGpuDriver =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "Only https:// links can be used" }
            val work = File(context.cacheDir, "gpu_driver_download").apply { deleteRecursively(); mkdirs() }
            try {
                val zip = File(work, "package.zip")
                download(url, zip, onProgress)

                val extracted = File(work, "x").apply { mkdirs() }
                extract(zip, extracted)

                val meta = File(extracted, "meta.json").takeIf { it.isFile }
                    ?: error("This is not a driver package (no meta.json)")
                val json = JSONObject(meta.readText())
                val library = json.getString("libraryName")
                require('/' !in library && library.isNotEmpty()) { "Bad library name in meta.json" }
                require(File(extracted, library).isFile) { "The package does not contain $library" }
                val minApi = json.optInt("minApi", 1)
                require(deviceApi >= minApi) { "This driver needs Android API $minApi, this device has $deviceApi" }
                val family = GpuFamily.ofLibrary(library)
                require(family == GpuFamily.UNKNOWN || gpuFamily == GpuFamily.UNKNOWN || family == gpuFamily) {
                    "This driver is for ${family.label} GPUs, this device has ${gpuFamily.label}"
                }

                val id = idOf(json.optString("name", library), json.optString("packageVersion", ""))
                val target = File(store(), id)
                target.deleteRecursively()
                target.mkdirs()
                // keep every file: vendor drivers (Qualcomm) load sibling libraries from the same folder
                extracted.listFiles { f -> f.isFile }.orEmpty().forEach { it.copyTo(File(target, it.name), overwrite = true) }
                File(target, "source.txt").writeText(url)

                ensureWorkingCopy(context, id)
                readInstalled(target)
            } finally {
                work.deleteRecursively()
            }
        }

    override suspend fun remove(id: String) {
        withContext(Dispatchers.IO) {
            // ids are folder names made by idOf(), never a path
            if ('/' !in id && id != "." && id != "..") {
                File(store(), id).deleteRecursively()
                File(workingRoot(context), id).deleteRecursively()
            }
        }
    }

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode == 200) { "Download failed (HTTP ${connection.responseCode})" }
            val total = connection.contentLengthLong
            require(total <= MAX_DOWNLOAD) { "The file is too large" }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        require(done <= MAX_DOWNLOAD) { "The file is too large" }
                        output.write(buffer, 0, n)
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Extracts the top-level files of the zip only, so entries with paths cannot write elsewhere. */
    private fun extract(zip: File, into: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            var written = 0L
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                if (name.isEmpty() || name.startsWith(".")) continue
                File(into, name).outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buffer)
                        if (n < 0) break
                        written += n
                        require(written <= MAX_DOWNLOAD * 2) { "The package is too large" }
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_DOWNLOAD = 100L * 1024 * 1024

        /** Persistent copies, next to the app's settings file. */
        private fun store() = File(OpenMWPaths.USER_FILE_STORAGE, "gpu_drivers").apply { mkdirs() }

        /** The GPU from its kernel driver's device node: kgsl on Adreno, kbase on Mali. */
        fun detectGpuFamily(): GpuFamily = when {
            File("/dev/kgsl-3d0").exists() -> GpuFamily.ADRENO
            File("/dev/mali0").exists() -> GpuFamily.MALI
            else -> GpuFamily.UNKNOWN
        }

        /** Copies libadrenotools loads from, in the app's private storage. */
        fun workingRoot(context: Context) = File(context.filesDir, "gpu_drivers")

        /** Folder name for a driver: letters, digits and dashes only. */
        fun idOf(name: String, packageVersion: String): String =
            "$name-$packageVersion".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "driver" }

        /** All drivers in the store, each with a working copy made if it was missing. */
        fun installedBlocking(context: Context): List<InstalledGpuDriver> =
            store().listFiles { f -> f.isDirectory }.orEmpty()
                .mapNotNull { dir ->
                    runCatching { readInstalled(dir).also { ensureWorkingCopy(context, it.id) } }.getOrNull()
                }
                .sortedBy { it.name.lowercase() }

        /**
         * Where a selected driver lives and which library to load, or null for the bundled one (also when the
         * driver is gone). Remakes the private working copy from the store if the app was reinstalled.
         */
        fun resolve(context: Context, id: String): Pair<File, String>? {
            if (id.isEmpty() || id == GpuDrivers.BUILTIN_ID || '/' in id || id == "." || id == "..") return null
            val installed = runCatching { readInstalled(File(store(), id)) }.getOrNull() ?: return null
            return runCatching { ensureWorkingCopy(context, id) }.getOrNull()?.let { it to installed.libraryName }
        }

        private fun ensureWorkingCopy(context: Context, id: String): File {
            val source = File(store(), id)
            val library = JSONObject(File(source, "meta.json").readText()).getString("libraryName")
            val work = File(workingRoot(context), id)
            val target = File(work, library)
            // the store's file is the reference: copy again when the working copy is missing or differs
            if (!target.isFile || target.length() != File(source, library).length()) {
                work.deleteRecursively()
                work.mkdirs()
                source.listFiles { f -> f.isFile && f.name != "source.txt" }.orEmpty()
                    .forEach { it.copyTo(File(work, it.name), overwrite = true) }
            }
            return work
        }

        private fun readInstalled(dir: File): InstalledGpuDriver {
            val json = JSONObject(File(dir, "meta.json").readText())
            val library = json.getString("libraryName")
            require(File(dir, library).isFile)
            return InstalledGpuDriver(
                id = dir.name,
                name = json.optString("name", library),
                version = json.optString("driverVersion", json.optString("packageVersion", "")),
                author = json.optString("author", ""),
                libraryName = library,
                minApi = json.optInt("minApi", 1),
                sourceUrl = File(dir, "source.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty(),
            )
        }
    }
}
