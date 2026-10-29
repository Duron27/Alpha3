package org.alpha3.launcher.tools

/**
 * Kotlin side of OpenMW's `openmw-navmeshtool` (apps/navmeshtool, components/navmeshtool/protocol.*).
 *
 * The navmesh generation itself lives in native code (Bullet/Recast/ESM loaders), so this file only
 * covers what a launcher needs: building the command-line options and decoding the binary
 * progress messages the tool writes to stderr when `--write-binary-log` is set.
 */
object NavMeshTool {

    /** Options of `openmw-navmeshtool`, mirroring main.cpp. Empty/default values are omitted. */
    data class Options(
        val data: List<String> = emptyList(),
        val dataLocal: String? = null,
        val fallbackArchives: List<String> = emptyList(),
        val content: List<String> = emptyList(),
        val encoding: String = "win1252",
        val fallback: List<String> = emptyList(),
        val threads: Int? = null,
        val processInteriorCells: Boolean = false,
        val removeUnusedTiles: Boolean = false,
        val writeBinaryLog: Boolean = true,
        val collectStats: Boolean = false,
        val worldspaceFilter: String = ".*",
    ) {
        fun toArgs(): List<String> = buildList {
            if (data.isNotEmpty()) { add("--data"); data.forEach { add(it) } }
            dataLocal?.let { add("--data-local"); add(it) }
            if (fallbackArchives.isNotEmpty()) { add("--fallback-archive"); addAll(fallbackArchives) }
            if (content.isNotEmpty()) { add("--content"); addAll(content) }
            add("--encoding"); add(encoding)
            if (fallback.isNotEmpty()) { add("--fallback"); addAll(fallback) }
            threads?.let { add("--threads"); add(it.toString()) }
            if (processInteriorCells) add("--process-interior-cells")
            if (removeUnusedTiles) add("--remove-unused-tiles")
            if (writeBinaryLog) add("--write-binary-log")
            if (collectStats) add("--collect-stats")
            if (worldspaceFilter != ".*") { add("--worldspace-filter"); add(worldspaceFilter) }
        }
    }

    sealed interface Message {
        data class ExpectedCells(val count: Long) : Message
        data class ProcessedCells(val count: Long) : Message
        data class ExpectedTiles(val count: Long) : Message
        data class GeneratedTiles(val count: Long) : Message
    }

    /** Thrown when the stream does not start with the `nvtm` magic. */
    class BadMagicException(message: String) : RuntimeException(message)

    /**
     * Incremental decoder for the stderr stream. Feed it chunks with [feed]; complete messages are
     * returned and partial ones are kept until more bytes arrive (like `deserialize` returning `begin`).
     */
    class Decoder {
        private var buffer = ByteArray(0)

        fun feed(chunk: ByteArray, length: Int = chunk.size): List<Message> {
            buffer += chunk.copyOf(length)
            val result = mutableListOf<Message>()
            var pos = 0
            while (true) {
                if (buffer.size - pos < HEADER_SIZE) break
                if (!MAGIC.indices.all { buffer[pos + it] == MAGIC[it] }) {
                    val got = MAGIC.indices.joinToString(" ") { formatMagicByte(buffer[pos + it]) }
                    throw BadMagicException("Bad navmeshtool message magic: $got")
                }
                val type = readLong(buffer, pos + MAGIC.size)
                val size = readLong(buffer, pos + MAGIC.size + 8)
                if (size < 0 || size > Int.MAX_VALUE) throw IllegalStateException("Bad message size: $size")
                val end = pos + HEADER_SIZE + size.toInt()
                if (buffer.size < end) break
                result += decode(type, buffer, pos + HEADER_SIZE, size.toInt())
                pos = end
            }
            if (pos > 0) buffer = buffer.copyOfRange(pos, buffer.size)
            return result
        }

        private fun decode(type: Long, data: ByteArray, offset: Int, size: Int): Message {
            if (size < 8) throw IllegalStateException("Message type $type too short: $size bytes")
            val count = readLong(data, offset)
            return when (type) {
                1L -> Message.ExpectedCells(count)
                2L -> Message.ProcessedCells(count)
                3L -> Message.ExpectedTiles(count)
                4L -> Message.GeneratedTiles(count)
                else -> throw IllegalStateException("Unsupported message type: $type")
            }
        }
    }

    /** Encodes a message exactly as the native tool does (useful for tests). */
    fun serialize(message: Message): ByteArray {
        val (type, count) = when (message) {
            is Message.ExpectedCells -> 1L to message.count
            is Message.ProcessedCells -> 2L to message.count
            is Message.ExpectedTiles -> 3L to message.count
            is Message.GeneratedTiles -> 4L to message.count
        }
        return MAGIC + longBytes(type) + longBytes(8) + longBytes(count)
    }

    private val MAGIC = byteArrayOf('n'.code.toByte(), 'v'.code.toByte(), 't'.code.toByte(), 'm'.code.toByte())
    private val HEADER_SIZE = MAGIC.size + 16

    // The native tool writes host-endian integers; every supported target (arm64/x86_64) is little-endian.
    private fun readLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun longBytes(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun formatMagicByte(b: Byte): String {
        val c = b.toInt() and 0xFF
        return if (c in 0x21..0x7E) "'${c.toChar()}'" else "0x" + c.toString(16).uppercase().padStart(2, '0')
    }
}
