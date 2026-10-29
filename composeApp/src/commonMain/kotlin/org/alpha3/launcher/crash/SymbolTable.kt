package org.alpha3.launcher.crash

/**
 * Function symbols of one native library, as written by the build (androidApp/build.gradle.kts, generateNativeSymbols):
 * a file named after the library's build id, one line per function: "<hex address> <hex size> <name>", sorted by address.
 * An address in a crash report is an offset from the load base of the module, which is what these addresses are too.
 */
class SymbolTable private constructor(
    private val addresses: LongArray,
    private val sizes: LongArray,
    private val names: Array<String>,
    val moduleName: String,
) {
    class Hit(val name: String, val offset: Long)

    /** The function containing [address], when there is one. */
    fun lookup(address: Long): Hit? {
        var low = 0
        var high = addresses.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (addresses[middle] <= address) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (found < 0) return null
        // Several symbols can share an address; the sized ones are the real functions
        var index = found
        while (index > 0 && addresses[index - 1] == addresses[found] && sizes[index] == 0L) index--
        val distance = address - addresses[index]
        val reach = if (sizes[index] > 0) sizes[index] else 0x100L
        return if (distance in 0 until reach) Hit(names[index], distance) else null
    }

    companion object {
        fun parse(text: String): SymbolTable {
            var module = ""
            val parsed = ArrayList<Triple<Long, Long, String>>()
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                if (line[0] == '#') {
                    if (line.startsWith("# module ")) module = line.removePrefix("# module ").trim()
                    continue
                }
                val first = line.indexOf(' ')
                val second = if (first > 0) line.indexOf(' ', first + 1) else -1
                if (second < 0) continue
                val address = line.substring(0, first).toLongOrNull(16) ?: continue
                val size = line.substring(first + 1, second).toLongOrNull(16) ?: continue
                parsed += Triple(address, size, line.substring(second + 1))
            }
            parsed.sortBy { it.first }
            return SymbolTable(
                LongArray(parsed.size) { parsed[it].first },
                LongArray(parsed.size) { parsed[it].second },
                Array(parsed.size) { parsed[it].third },
                module,
            )
        }
    }
}

/** Symbol tables shipped inside the app, by build id. Until releases keep theirs online, the build copies them into the app. */
expect fun readBundledSymbols(context: org.alpha3.launcher.utils.PlatformContext, buildId: String): String?

/**
 * The symbol table of a build that is not bundled in the app, kept in the GitLab package registry by the release pipeline
 * (.gitlab-ci.yml) under the build id. A table that was fetched once is kept next to the crash files; null when the build is
 * unknown there or there is no network.
 */
expect fun readOnlineSymbols(buildId: String): String?

const val SYMBOL_PACKAGE_URL = "https://gitlab.com/api/v4/projects/duron27%2Falpha3_aio/packages/generic/native-symbols"
