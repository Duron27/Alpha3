package org.alpha3.launcher.launch

/** How an option's value is edited in the Launch options dialog. */
enum class OptionKind {
    /** Present or absent, no value. */
    FLAG,

    /** One of [OptionSpec.options]. */
    CHOICE,
    NUMBER,
    TEXT,

    /** One of the saved games found in the saves folder; the value is the file's path. */
    SAVE,
}

/**
 * An environment variable or a command line option the game reads at start-up.
 * [defaultValue] is what is set when the switch is turned on; [engineDefault] is what the game does without it.
 */
data class OptionSpec(
    val name: String,
    val group: String,
    val source: String,
    val description: String,
    val kind: OptionKind,
    val defaultValue: String,
    val engineDefault: String,
    val options: List<String> = emptyList(),
    /** On the first run, before anything has been saved. */
    val onByDefault: Boolean = false,
)

data class OptionEntry(val enabled: Boolean, val value: String)

/** The saved form of the entries: one line per option, "name<TAB>1 or 0<TAB>value", tabs and newlines escaped. */
internal object OptionCodec {
    private fun escape(text: String) = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(text: String) = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(text[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    fun encode(entries: Map<String, OptionEntry>): String =
        entries.entries.joinToString("\n") { (name, entry) ->
            "${escape(name)}\t${if (entry.enabled) 1 else 0}\t${escape(entry.value)}"
        }

    fun decode(text: String): Map<String, OptionEntry> {
        val result = LinkedHashMap<String, OptionEntry>()
        for (line in text.split('\n')) {
            val parts = line.split('\t', limit = 3)
            if (parts.size < 2 || parts[0].isEmpty()) continue
            result[unescape(parts[0])] = OptionEntry(parts[1] == "1", if (parts.size > 2) unescape(parts[2]) else "")
        }
        return result
    }
}

/** Splits a line the way a shell would: spaces separate, "double" or 'single' quotes keep spaces together. */
fun tokenizeArguments(line: String): List<String> {
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    for (c in line) {
        when {
            quote != null -> if (c == quote) quote = null else current.append(c)
            c == '"' || c == '\'' -> {
                quote = c
                started = true
            }
            c.isWhitespace() -> if (started || current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
                started = false
            }
            else -> current.append(c)
        }
    }
    if (started || current.isNotEmpty()) tokens += current.toString()
    return tokens
}
