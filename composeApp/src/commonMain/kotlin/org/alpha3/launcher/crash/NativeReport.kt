package org.alpha3.launcher.crash

/** A module of the crashed process: load base and the build id that names its symbol file. */
class NativeModule(val path: String, val base: ULong, val buildId: String)

/** A frame line of our own report: "Fault frame #3: /path/libopenmw.so + 0x4e68188 [symbol]". */
class NativeFrame(val label: String, val module: String, val offset: Long, val systemSymbol: String)

/** Our own crash report (cpp/Alpha3/BackTrace.cpp, crash_report.txt), split into what the viewer shows. */
class NativeReport(val raw: String) {
    val lines: List<String> = raw.lines()
    val modules: Map<String, NativeModule>
    val frames: List<NativeFrame>

    init {
        val moduleMap = LinkedHashMap<String, NativeModule>()
        val frameList = ArrayList<NativeFrame>()
        val framePattern = Regex("""^\s*Fault (PC|LR|frame #\d+): (\S+) \+ 0x([0-9a-fA-F]+) \[(.*)]\s*$""")
        for (line in lines) {
            if (line.startsWith("module ")) {
                val parts = line.split(' ', limit = 4)
                if (parts.size == 4) {
                    val base = parts[1].removePrefix("0x").toULongOrNull(16) ?: continue
                    moduleMap[parts[3]] = NativeModule(parts[3], base, if (parts[2] == "-") "" else parts[2])
                }
            } else {
                val match = framePattern.find(line) ?: continue
                frameList += NativeFrame(
                    match.groupValues[1], match.groupValues[2], match.groupValues[3].toLong(16), match.groupValues[4],
                )
            }
        }
        modules = moduleMap
        frames = frameList
    }

    /** The first line starting with [prefix], without it. */
    fun value(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()

    /** The lines between a "---- title ..." header and the next header or the end marker. */
    fun section(titleStart: String): List<String> {
        val start = lines.indexOfFirst { it.startsWith("---- $titleStart") }
        if (start < 0) return emptyList()
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("---- ") || it.startsWith("==========") }
        return (if (end < 0) rest else rest.take(end)).map { it.trimStart('\t') }
    }
}
