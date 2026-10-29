package org.alpha3.launcher.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.alpha3.launcher.io.File
import org.alpha3.launcher.utils.availableCpuCores
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Converts every DDS under a folder to ASTC KTX with [KramTool]. */
object KramBatch {

    enum class TaskResult { SUCCESS, FAILED, SKIPPED_SMALL, SKIPPED_EXISTS }

    enum class LogType { INFO, SUCCESS, WARNING, ERROR }

    data class LogMessage(val message: String, val type: LogType)

    data class Config(
        /** Textures with a side smaller than this (px) are left alone. */
        val sizeThreshold: Int = 16,
        val blockSize: KramTool.AstcFormat = KramTool.AstcFormat.Astc12x12,
        val astcQuality: Int = 50,
        /** Delete the DDS once the KTX exists. */
        val inPlace: Boolean = true,
        val backupOriginal: Boolean = false,
    )

    data class Result(
        val total: Int,
        val processed: Int,
        val skippedSmall: Int,
        val skippedExisting: Int,
        val failed: Int,
        val elapsedSeconds: Double,
    )

    /**
     * Where a batch stands. Sizes are the DDS file sizes, used as the measure of work because the time
     * to convert a texture grows with its pixel count.
     */
    data class Progress(
        /** What is going on: "Extracting" or "Converting". */
        val phase: String = "Converting",
        val completedFiles: Int = 0,
        val totalFiles: Int = 0,
        /** Files being converted right now. */
        val inFlight: List<String> = emptyList(),
        val totalBytes: Long = 0,
        /** Bytes of files that are finished, including skipped ones. */
        val completedBytes: Long = 0,
        /** Bytes of files that were actually converted (not skipped). */
        val convertedBytes: Long = 0,
    ) {
        /** 0..1 */
        val fraction: Float get() = if (totalBytes <= 0) 0f else (completedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

        /**
         * Rough time left, from how fast the converted files went so far. Null until a file has been
         * converted. Skipped files are counted as work still to do until they are reached, so the
         * estimate starts high when many files will be skipped and settles as the batch moves on.
         */
        fun etaSeconds(elapsedSeconds: Double): Double? {
            if (convertedBytes <= 0 || elapsedSeconds <= 0) return null
            val remaining = (totalBytes - completedBytes).coerceAtLeast(0)
            return elapsedSeconds * remaining / convertedBytes
        }
    }

    /** Block sizes offered in the UI. */
    val BLOCK_SIZES = listOf(
        KramTool.AstcFormat.Astc4x4, KramTool.AstcFormat.Astc5x5, KramTool.AstcFormat.Astc6x6,
        KramTool.AstcFormat.Astc8x8, KramTool.AstcFormat.Astc10x10, KramTool.AstcFormat.Astc12x12,
    )

    /**
     * Unpacks a Morrowind BSA into [outDir] (textures only, unless [everything]), then converts the DDS files that
     * came out with [run]. Progress callbacks run on the main dispatcher like [run]'s.
     */
    suspend fun runFromBsa(
        runner: KramTool.Runner,
        bsaPath: String,
        outDir: String,
        config: Config,
        everything: Boolean,
        onProgress: (Progress) -> Unit,
        onLog: (LogMessage) -> Unit,
    ): Result {
        suspend fun log(text: String, type: LogType = LogType.INFO) =
            withContext(Dispatchers.Main) { onLog(LogMessage(text, type)) }

        log("=== Extracting ${bsaPath.substringAfterLast('/')} ===")
        log("Into: $outDir")
        val extracted = try {
            extractBsa(bsaPath, outDir, everything, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a bad archive, or the storage refused (full, no permission)
            log(e.message ?: "The archive could not be unpacked", LogType.ERROR)
            return Result(0, 0, 0, 0, 1, 0.0)
        }
        log("Extracted $extracted files")

        // only the DDS files can be converted; with "everything" the rest is just unpacked next to them
        return run(runner, outDir, config, onProgress, onLog)
    }

    /** Unpacks the archive with several files in flight at once, see [BsaExtractor]. */
    private suspend fun extractBsa(bsaPath: String, outDir: String, everything: Boolean, onProgress: (Progress) -> Unit): Int {
        // extracting mostly waits for storage, so more workers than cores keeps the cores and the storage busy
        val workers = (availableCpuCores() * 2).coerceIn(4, 16)
        return BsaExtractor.extract(
            archive = bsaPath,
            outDir = outDir,
            workers = workers,
            accept = { everything || it.endsWith(".dds", ignoreCase = true) },
        ) { report ->
            withContext(Dispatchers.Main) {
                onProgress(
                    Progress(
                        phase = "Extracting",
                        completedFiles = report.doneFiles,
                        totalFiles = report.totalFiles,
                        inFlight = listOf(report.lastName),
                        totalBytes = report.totalBytes,
                        completedBytes = report.doneBytes,
                    ),
                )
            }
        }
    }

    fun findTextures(baseDir: String): List<File> {
        val dir = File(baseDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walk().filter { it.isFile && it.extension.equals("dds", ignoreCase = true) }.toList()
    }

    /**
     * Callbacks are invoked on the main dispatcher, so they can write UI state directly.
     */
    suspend fun run(
        runner: KramTool.Runner,
        baseDir: String,
        config: Config,
        onProgress: (Progress) -> Unit,
        onLog: (LogMessage) -> Unit,
    ): Result = coroutineScope {
        val start = TimeSource.Monotonic.markNow()
        suspend fun log(text: String, type: LogType = LogType.INFO) =
            withContext(Dispatchers.Main) { onLog(LogMessage(text, type)) }

        log("=== Texture to ASTC Converter ===")
        log("Searching for textures in: $baseDir")
        val files = withContext(Dispatchers.IO) { findTextures(baseDir) }
        log("Found ${files.size} textures to evaluate")
        log("  Size threshold: > ${config.sizeThreshold}px, block ${config.blockSize.arg}, quality ${config.astcQuality}")
        log("  Concurrency limit: ${runner.maxConcurrent} threads")

        val sizes = files.associateWith { it.length() }
        var progress = Progress(totalFiles = files.size, totalBytes = sizes.values.sum())
        withContext(Dispatchers.Main) { onProgress(progress) }

        val semaphore = Semaphore(runner.maxConcurrent)
        // biggest first: the long jobs start early and the small ones fill the gaps at the end
        val results = files.sortedByDescending { sizes.getValue(it) }.map { file ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withContext(Dispatchers.Main) {
                        progress = progress.copy(inFlight = progress.inFlight + file.name)
                        onProgress(progress)
                    }
                    val result = processFile(runner, file, config) { log(it) }
                    withContext(Dispatchers.Main) {
                        val bytes = sizes.getValue(file)
                        val converted = result == TaskResult.SUCCESS || result == TaskResult.FAILED
                        progress = progress.copy(
                            completedFiles = progress.completedFiles + 1,
                            inFlight = progress.inFlight - file.name,
                            completedBytes = progress.completedBytes + bytes,
                            convertedBytes = progress.convertedBytes + if (converted) bytes else 0,
                        )
                        onProgress(progress)
                    }
                    result
                }
            }
        }.awaitAll()

        val processed = results.count { it == TaskResult.SUCCESS }
        val existing = results.count { it == TaskResult.SKIPPED_EXISTS }
        val small = results.count { it == TaskResult.SKIPPED_SMALL }
        val failed = results.count { it == TaskResult.FAILED }
        val elapsed = start.elapsedNow().inWholeMilliseconds / 1000.0

        log("=== Batch Finished ===")
        log("Successfully processed: $processed", LogType.SUCCESS)
        log("Skipped (already converted): $existing")
        log("Skipped (too small): $small")
        if (failed > 0) log("Failed: $failed", LogType.ERROR)
        log("Execution time: ${(elapsed * 100).toLong() / 100.0}s")
        Result(files.size, processed, small, existing, failed, elapsed)
    }

    private suspend fun processFile(
        runner: KramTool.Runner,
        file: File,
        config: Config,
        log: suspend (String) -> Unit,
    ): TaskResult {
        val outputFile = File(file.parent, "${file.nameWithoutExtension}.ktx")
        if (outputFile.exists()) {
            log("Skipping ${file.name}: output exists")
            return TaskResult.SKIPPED_EXISTS
        }

        // what kram printed, kept so a failure can say why
        val output = mutableListOf<String>()
        fun why() = output.filter { it.isNotBlank() }.takeLast(3).joinToString(" | ").ifEmpty { "kram printed nothing" }

        val info = KramTool.info(runner, file.absolutePath) { output += it }
        if (info == null) {
            log("Could not read ${file.name}: ${why()}")
            return TaskResult.FAILED
        }
        if (info.width < config.sizeThreshold || info.height < config.sizeThreshold) {
            log("Skipping ${file.name}: too small (${info.width}x${info.height})")
            return TaskResult.SKIPPED_SMALL
        }

        log("Converting ${file.name} (${info.width}x${info.height}, ${info.format})")
        if (config.backupOriginal) {
            file.copyTo(File(file.parent, "${file.nameWithoutExtension}.backup.${file.extension}"), overwrite = true)
        }

        val options = KramTool.EncodeOptions(
            input = file.absolutePath,
            output = outputFile.absolutePath,
            format = config.blockSize,
            quality = config.astcQuality,
            encoder = "astcenc",
            verbose = true,
        )
        output.clear()
        val ok = try {
            KramTool.convert(runner, file.absolutePath, outputFile.absolutePath, options) { output += it }
        } catch (e: Exception) {
            output += e.message ?: "unknown error"
            false
        }
        if (!ok) {
            log("Failed ${file.name}: ${why()}")
            return TaskResult.FAILED
        }
        if (config.inPlace) file.delete()
        return TaskResult.SUCCESS
    }
}
