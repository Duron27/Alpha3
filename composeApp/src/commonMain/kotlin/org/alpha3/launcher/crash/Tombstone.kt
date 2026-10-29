package org.alpha3.launcher.crash

/** One backtrace frame of a tombstone. [functionName] is empty for code the system had no symbols for (our stripped libraries). */
class TombstoneFrame(
    val relPc: Long,
    val pc: ULong,
    val functionName: String,
    val functionOffset: Long,
    val fileName: String,
    val buildId: String,
)

class TombstoneThread(
    val id: Int,
    val name: String,
    val registers: List<Pair<String, ULong>>,
    val frames: List<TombstoneFrame>,
)

/** What the system recorded about a native crash (system/core/debuggerd/proto/tombstone.proto), the parts worth reading. */
class Tombstone(
    val fingerprint: String,
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val uid: Int,
    val commandLine: List<String>,
    val signalNumber: Int,
    val signalName: String,
    val signalCode: Int,
    val signalCodeName: String,
    val hasSender: Boolean,
    val senderPid: Int,
    val senderUid: Int,
    val faultAddress: ULong?,
    val abortMessage: String,
    val causes: List<String>,
    val threads: List<TombstoneThread>,
    val mappingCount: Int,
    val openFds: List<String>,
) {
    val crashingThread: TombstoneThread? get() = threads.firstOrNull { it.id == tid }
}

object TombstoneParser {
    private class Wire(val data: ByteArray, var position: Int, val end: Int) {
        fun more() = position < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                check(position < end && shift < 70) { "bad varint" }
                val byte = data[position++].toInt()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Walks one message; [onField] gets the field number and, for a length-delimited field, its byte range. */
        inline fun fields(onField: (field: Int, value: Long, from: Int, to: Int) -> Unit) {
            while (more()) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> onField(field, varint(), -1, -1)
                    1 -> { check(position + 8 <= end); position += 8 }
                    5 -> { check(position + 4 <= end); position += 4 }
                    2 -> {
                        val length = varint().toInt()
                        check(length >= 0 && position + length <= end) { "short field" }
                        val from = position
                        position += length
                        onField(field, 0, from, from + length)
                    }
                    else -> error("wire type ${key and 7}")
                }
            }
        }
    }

    private fun ByteArray.text(from: Int, to: Int) = decodeToString(from, to)

    fun parse(data: ByteArray): Tombstone? = try {
        read(data)
    } catch (e: Exception) {
        null
    }

    private fun read(data: ByteArray): Tombstone {
        var fingerprint = ""
        var timestamp = ""
        var pid = 0
        var tid = 0
        var uid = 0
        val command = mutableListOf<String>()
        var signalNumber = 0
        var signalName = ""
        var signalCode = 0
        var signalCodeName = ""
        var hasSender = false
        var senderPid = 0
        var senderUid = 0
        var hasFault = false
        var fault = 0uL
        var abort = ""
        val causes = mutableListOf<String>()
        val threads = mutableListOf<TombstoneThread>()
        var mappings = 0
        val fds = mutableListOf<String>()

        Wire(data, 0, data.size).fields { field, value, from, to ->
            when (field) {
                2 -> fingerprint = data.text(from, to)
                4 -> timestamp = data.text(from, to)
                5 -> pid = value.toInt()
                6 -> tid = value.toInt()
                7 -> uid = value.toInt()
                9 -> command += data.text(from, to)
                10 -> Wire(data, from, to).fields { f, v, a, b ->
                    when (f) {
                        1 -> signalNumber = v.toInt()
                        2 -> signalName = data.text(a, b)
                        3 -> signalCode = v.toInt()
                        4 -> signalCodeName = data.text(a, b)
                        5 -> hasSender = v != 0L
                        6 -> senderUid = v.toInt()
                        7 -> senderPid = v.toInt()
                        8 -> hasFault = v != 0L
                        9 -> fault = v.toULong()
                    }
                }
                14 -> abort = data.text(from, to)
                15 -> Wire(data, from, to).fields { f, _, a, b -> if (f == 1) causes += data.text(a, b) }
                16 -> readThread(data, from, to)?.let { threads += it }
                17 -> mappings++
                19 -> Wire(data, from, to).fields { f, _, a, b -> if (f == 2) fds += data.text(a, b) }
            }
        }
        return Tombstone(
            fingerprint, timestamp, pid, tid, uid, command, signalNumber, signalName, signalCode, signalCodeName,
            hasSender, senderPid, senderUid, if (hasFault) fault else null, abort, causes, threads, mappings, fds,
        )
    }

    /** One entry of the threads map: key = thread id, value = the thread. */
    private fun readThread(data: ByteArray, from: Int, to: Int): TombstoneThread? {
        var id = 0
        var body: IntArray? = null
        Wire(data, from, to).fields { f, v, a, b ->
            if (f == 1) id = v.toInt() else if (f == 2) body = intArrayOf(a, b)
        }
        val range = body ?: return null
        var name = ""
        val registers = mutableListOf<Pair<String, ULong>>()
        val frames = mutableListOf<TombstoneFrame>()
        Wire(data, range[0], range[1]).fields { f, v, a, b ->
            when (f) {
                1 -> id = v.toInt()
                2 -> name = data.text(a, b)
                3 -> {
                    var registerName = ""
                    var registerValue = 0uL
                    Wire(data, a, b).fields { rf, rv, ra, rb ->
                        if (rf == 1) registerName = data.text(ra, rb) else if (rf == 2) registerValue = rv.toULong()
                    }
                    registers += registerName to registerValue
                }
                4 -> {
                    var relPc = 0L
                    var pc = 0uL
                    var function = ""
                    var offset = 0L
                    var file = ""
                    var buildId = ""
                    Wire(data, a, b).fields { ff, fv, fa, fb ->
                        when (ff) {
                            1 -> relPc = fv
                            2 -> pc = fv.toULong()
                            4 -> function = data.text(fa, fb)
                            5 -> offset = fv
                            6 -> file = data.text(fa, fb)
                            8 -> buildId = data.text(fa, fb)
                        }
                    }
                    frames += TombstoneFrame(relPc, pc, function, offset, file, buildId)
                }
            }
        }
        return TombstoneThread(id, name, registers, frames)
    }
}
