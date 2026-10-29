package org.alpha3.launcher.crash

import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.utils.PlatformContext

/** A frame ready to show: where it is, and the function once a symbol table is found for the module. */
class ShownFrame(
    val label: String,
    val module: String,
    val offset: Long,
    val buildId: String,
    val function: String,
    val functionOffset: Long,
    /** "system" when the tombstone already named it, "symbols" when the bundled table did, "" when nothing did. */
    val source: String,
) {
    val moduleName: String get() = module.substringAfterLast('/')

    fun text(): String {
        val where = "$moduleName + 0x${offset.toString(16)}"
        return if (function.isEmpty()) where else "$where  $function" + if (functionOffset > 0) "+$functionOffset" else ""
    }
}

class ShownThread(val id: Int, val name: String, val frames: List<ShownFrame>, val crashed: Boolean)

/** Everything known about the previous crash, from our report, the system's tombstone and OpenMW's own files. */
class CrashData(
    val native: NativeReport?,
    val tombstone: Tombstone?,
    /** The system's exit record (reason, status, memory) without the tombstone text. */
    val exitRecord: String?,
    val javaCrash: String?,
    val rendererStatus: String?,
    val openmwLogTail: List<String>,
    val crashTimeMillis: Long,
    val threads: List<ShownThread>,
    val registers: List<Pair<String, ULong>>,
    val symbolsFound: Int,
    val symbolsMissing: List<String>,
) {
    val isEmpty: Boolean get() = native == null && tombstone == null && exitRecord == null && javaCrash == null

    val crashingThread: ShownThread? get() = threads.firstOrNull { it.crashed }
}

object CrashLoader {
    private const val LOG_TAIL_BYTES = 256L * 1024
    private const val LOG_TAIL_LINES = 100

    private fun read(name: String): File? = File(OpenMWPaths.ACTIVE_CONFIG, name).takeIf { it.exists() && it.length() > 0 }

    fun load(context: PlatformContext): CrashData {
        val native = read("crash_report.txt")?.let { runCatching { NativeReport(it.readText()) }.getOrNull() }
        val tombstoneFile = read("tombstone_last.pb")
        val tombstone = tombstoneFile?.let { runCatching { TombstoneParser.parse(it.readBytes()) }.getOrNull() }
        val exitRecord = read("tombstone_last.txt")?.let {
            runCatching { it.readText().substringBefore("---- Tombstone").trimEnd() }.getOrNull()
        }
        val java = read("crash.log")?.let { runCatching { it.readText() }.getOrNull() }
        val renderer = read("renderer.status")?.let { runCatching { it.readText().trim() }.getOrNull() }
        val logTail = File(OpenMWPaths.OPENMW_LOG).takeIf { it.exists() }?.let {
            runCatching { it.readTailBytes(LOG_TAIL_BYTES).decodeToString().lines().dropLast(1).takeLast(LOG_TAIL_LINES) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val time = listOfNotNull(
            read("crash_report.txt")?.lastModified(), tombstoneFile?.lastModified(), read("crash.log")?.lastModified(),
        ).maxOrNull() ?: 0L

        // Symbol tables are loaded once per build id
        val tables = HashMap<String, SymbolTable?>()
        val missing = linkedSetOf<String>()
        var found = 0
        fun table(buildId: String, module: String): SymbolTable? {
            if (buildId.isEmpty()) return null
            return tables.getOrPut(buildId) {
                (readBundledSymbols(context, buildId) ?: readOnlineSymbols(buildId))?.let { runCatching { SymbolTable.parse(it) }.getOrNull() }
            }.also { if (it == null) missing += "${module.substringAfterLast('/')} ($buildId)" }
        }

        fun symbolize(label: String, module: String, offset: Long, buildId: String, system: String, systemOffset: Long): ShownFrame {
            if (system.isNotEmpty() && system != "?") {
                return ShownFrame(label, module, offset, buildId, system, systemOffset, "system")
            }
            val hit = table(buildId, module)?.lookup(offset)
            if (hit != null) found++
            return ShownFrame(label, module, offset, buildId, hit?.name ?: "", hit?.offset ?: 0, if (hit != null) "symbols" else "")
        }

        val threads = ArrayList<ShownThread>()
        var registers = emptyList<Pair<String, ULong>>()
        if (tombstone != null) {
            for (thread in tombstone.threads.sortedWith(compareByDescending<TombstoneThread> { it.id == tombstone.tid }.thenBy { it.id })) {
                val frames = thread.frames.mapIndexed { index, frame ->
                    symbolize("#$index", frame.fileName, frame.relPc, frame.buildId, frame.functionName, frame.functionOffset)
                }
                threads += ShownThread(thread.id, thread.name, frames, thread.id == tombstone.tid)
            }
            registers = tombstone.crashingThread?.registers ?: emptyList()
        } else if (native != null) {
            // No tombstone: the frames of our own report
            val frames = native.frames.map { frame ->
                val buildId = native.modules[frame.module]?.buildId ?: ""
                symbolize(frame.label, frame.module, frame.offset, buildId, frame.systemSymbol, 0)
            }
            val name = native.value("Thread:") ?: "crashed thread"
            threads += ShownThread(0, name, frames, true)
        }
        return CrashData(
            native, tombstone, exitRecord, java, renderer, logTail, time, threads, registers, found, missing.toList(),
        )
    }

    /** All of it as plain text, to copy out of the app. */
    fun toText(data: CrashData): String = buildString {
        appendLine(summary(data).joinToString("\n") { "${it.first}: ${it.second}" })
        data.crashingThread?.let { thread ->
            appendLine()
            appendLine("---- Backtrace of ${thread.name} (tid ${thread.id}) ----")
            thread.frames.forEach { appendLine("${it.label} ${it.text()}") }
        }
        data.native?.let {
            appendLine()
            appendLine("---- Native crash report ----")
            appendLine(it.raw)
        }
        data.exitRecord?.let {
            appendLine()
            appendLine(it)
        }
        data.javaCrash?.let {
            appendLine()
            appendLine("---- Java crash log ----")
            appendLine(it)
        }
        if (data.openmwLogTail.isNotEmpty()) {
            appendLine()
            appendLine("---- OpenMW log (tail) ----")
            data.openmwLogTail.forEach { appendLine(it) }
        }
    }

    /** The few facts that say what happened, label to value. */
    fun summary(data: CrashData): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        val native = data.native
        val tombstone = data.tombstone
        native?.value("Time:")?.let { list += "Time" to it }
        if (tombstone != null) {
            val signal = "${tombstone.signalName} (${tombstone.signalNumber}), ${tombstone.signalCodeName}"
            list += "Signal" to signal
            tombstone.faultAddress?.let { list += "Fault address" to "0x${it.toString(16)}" }
            if (tombstone.hasSender) list += "Sent by" to "pid ${tombstone.senderPid}, uid ${tombstone.senderUid}"
            if (tombstone.abortMessage.isNotEmpty()) list += "Abort message" to tombstone.abortMessage
            tombstone.causes.forEach { list += "Cause" to it }
            data.crashingThread?.let { list += "Crashed thread" to "${it.name} (tid ${it.id})" }
        } else if (native != null) {
            native.value("Signal:")?.let { list += "Signal" to it }
            native.value("Code:")?.let { list += "Code" to it }
            native.value("Fault Address:")?.let { list += "Fault address" to it }
            native.value("Thread:")?.let { list += "Crashed thread" to it }
        }
        native?.value("App:")?.let { list += "App" to it }
        native?.value("Device:")?.let { list += "Device" to it }
        native?.value("Android Version:")?.let { list += "Android" to it }
        data.exitRecord?.lines()?.firstOrNull { it.startsWith("Reason:") }?.let { list += "System recorded" to it.removePrefix("Reason:").trim() }
        data.exitRecord?.lines()?.firstOrNull { it.startsWith("Memory at exit:") }?.let { list += "Memory" to it.removePrefix("Memory at exit:").trim() }
        data.rendererStatus?.let { status ->
            val renderer = status.lines().firstOrNull { it.trimStart().startsWith("actual") || it.trimStart().startsWith("renderer") }
            list += "Renderer" to (renderer?.trim() ?: status.lines().first())
        }
        return list
    }
}
