package org.alpha3.launcher.tools

import okio.Path.Companion.toPath
import org.alpha3.launcher.io.systemFileSystem

/**
 * Kotlin front end for the native kram library (built from kramc with KRAM_BUILD_SHARED), used to convert
 * textures (DDS/PNG/KTX) to ASTC in a KTX/KTX2 container.
 *
 * The encoder itself (astc-encoder, bc7enc, etc2comp, ...) stays native. This builds kram's command-line
 * arguments and hands them to a platform [Runner], which calls the library in-process; [convert] does one
 * DDS -> ASTC KTX file.
 */
object KramTool {

    /** Calls into the native kram library; supplied by the platform (null where it is not available). */
    interface Runner {
        /** How many conversions can run side by side. */
        val maxConcurrent: Int get() = 4

        /**
         * Runs kram with [args] (the arguments after the program name, e.g. `encode -f astc8x8 ...`),
         * passing each line kram prints to [onLine]; returns kram's exit code (0 = success).
         */
        suspend fun run(args: List<String>, onLine: (String) -> Unit = {}): Int
    }

    data class TextureInfo(val width: Int, val height: Int, val format: String, val mipCount: Int)

    /** Parses the output of `kram info`. */
    fun parseInfo(output: String): TextureInfo? {
        var width = 0
        var height = 0
        var format = "unknown"
        var mips = 0
        for (line in output.lineSequence()) {
            when {
                "dims:" in line -> {
                    val parts = line.substringAfter("dims:").trim().split("x")
                    width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                }
                "fmtk:" in line -> format = line.substringAfter("fmtk:").trim()
                "mips:" in line -> mips = line.substringAfter("mips:").trim().toIntOrNull() ?: 0
            }
        }
        return if (width == 0 || height == 0) null else TextureInfo(width, height, format, mips)
    }

    /** Reads the size, format and mip count of a texture (`info -i file`). */
    suspend fun info(runner: Runner, input: String, onLine: (String) -> Unit = {}): TextureInfo? {
        val out = StringBuilder()
        runner.run(infoArgs(input)) { out.appendLine(it); onLine(it) }
        return parseInfo(out.toString())
    }

    /**
     * Converts [input] (e.g. a DDS, including BC-compressed ones) to an ASTC [output] (.ktx/.ktx2) in a single
     * run: kram decodes block-compressed input in memory when [EncodeOptions.format] is given.
     * @return true when the output was written.
     */
    suspend fun convert(
        runner: Runner,
        input: String,
        output: String,
        options: EncodeOptions,
        onLine: (String) -> Unit = {},
    ): Boolean {
        val code = runner.run(encodeArgs(options.copy(input = input, output = output)), onLine)
        val size = systemFileSystem.metadataOrNull(output.toPath())?.size ?: 0L
        return code == 0 && size > 0
    }

    /** Arguments for `decode -i in -o out`. kram can decode block-compressed input itself during [convert]. */
    fun decodeArgs(input: String, output: String): List<String> = listOf("decode", "-i", input, "-o", output)

    fun infoArgs(input: String): List<String> = listOf("info", "-i", input)

    enum class TextureType(val arg: String) { Texture2D("2d"), Array2D("2darray"), Cube("cube"), Volume("3d") }

    /** ASTC block sizes accepted by `-f` (kram format names). */
    enum class AstcFormat(val arg: String) {
        Astc4x4("astc4x4"), Astc5x4("astc5x4"), Astc5x5("astc5x5"), Astc6x5("astc6x5"), Astc6x6("astc6x6"),
        Astc8x5("astc8x5"), Astc8x6("astc8x6"), Astc8x8("astc8x8"),
        Astc10x5("astc10x5"), Astc10x6("astc10x6"), Astc10x8("astc10x8"), Astc10x10("astc10x10"),
        Astc12x10("astc12x10"), Astc12x12("astc12x12"),
    }

    sealed interface Supercompression {
        /** KTX2 only. */
        data class Zstd(val level: Int = 0) : Supercompression
        data class Zlib(val level: Int = 5) : Supercompression
    }

    data class EncodeOptions(
        val input: String,
        /** Extension picks the container: `.ktx` or `.ktx2`. */
        val output: String,
        val format: AstcFormat = AstcFormat.Astc4x4,
        val type: TextureType = TextureType.Texture2D,
        /** 0..100, higher is slower and better. kram's own examples use 49. */
        val quality: Int? = 49,
        val srgb: Boolean = false,
        val premultiplyAlpha: Boolean = false,
        val normalMap: Boolean = false,
        val hdr: Boolean = false,
        val signed: Boolean = false,
        val mipMax: Int? = null,
        val mipMin: Int? = null,
        val mipSkip: Int? = null,
        val noMips: Boolean = false,
        val flipY: Boolean = false,
        val swizzle: String? = null,
        val encoder: String? = null,
        val supercompression: Supercompression? = null,
        val verbose: Boolean = false,
    ) {
        init {
            require(quality == null || quality in 0..100) { "quality must be 0..100" }
            require(mipMax == null || mipMax in 1..65536) { "mipMax must be 1..65536" }
            require(supercompression == null || output.endsWith(".ktx2", true)) {
                "supercompression needs a .ktx2 output"
            }
        }
    }

    /** Arguments for [Runner.run], starting with the `encode` subcommand. */
    fun encodeArgs(o: EncodeOptions): List<String> = buildList {
        add("encode")
        add("-f"); add(o.format.arg)
        add("-type"); add(o.type.arg)
        o.quality?.let { add("-quality"); add(it.toString()) }
        if (o.srgb) add("-srgb")
        if (o.premultiplyAlpha) add("-premul")
        if (o.normalMap) add("-normal")
        if (o.hdr) add("-hdr")
        if (o.signed) add("-signed")
        o.mipMax?.let { add("-mipmax"); add(it.toString()) }
        o.mipMin?.let { add("-mipmin"); add(it.toString()) }
        o.mipSkip?.let { add("-mipskip"); add(it.toString()) }
        if (o.noMips) add("-mipnone")
        if (o.flipY) add("-flip")
        o.swizzle?.let { add("-swizzle"); add(it) }
        o.encoder?.let { add("-encoder"); add(it) }
        when (val s = o.supercompression) {
            is Supercompression.Zstd -> { add("-zstd"); add(s.level.toString()) }
            is Supercompression.Zlib -> { add("-zlib"); add(s.level.toString()) }
            null -> {}
        }
        if (o.verbose) add("-v")
        add("-i"); add(o.input)
        add("-o"); add(o.output)
    }

    /** Default output path: same name as [input] with a `.ktx` extension. */
    fun defaultOutput(input: String, extension: String = "ktx"): String =
        input.substringBeforeLast('.', input) + "." + extension
}
