// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.merge

/** One edit to a text: [removed] lines starting at line [oldStart] of the version it was made against become [added]. */
internal class TextHunk(
    val oldStart: Int, val removed: List<String>, val added: List<String>,
    /** The (up to two) lines before the edit, and whether the edit ran to the end of the text; they place pure insertions. */
    val before: List<String> = emptyList(), val atEnd: Boolean = false,
)

private fun asString(b: ByteArray) = CharArray(b.size) { (b[it].toInt() and 0xff).toChar() }.concatToString()
private fun asBytes(s: String) = ByteArray(s.length) { s[it].code.toByte() }

/** Lines with their line ends kept, so putting them back together gives the exact text. */
private fun linesOf(s: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (i in s.indices) if (s[i] == '\n') { out.add(s.substring(start, i + 1)); start = i + 1 }
    if (start < s.length) out.add(s.substring(start))
    return out
}

private const val MAX_CELLS = 4_000_000L

/** Items [aFrom, aTo) of the old list became items [bFrom, bTo) of the new one. */
internal class EditRange(val aFrom: Int, val aTo: Int, val bFrom: Int, val bTo: Int)

/** The edits that turn [a] into [b] (a longest common subsequence diff), or null when the lists are too big to compare. */
internal fun diffRanges(a: List<String>, b: List<String>): List<EditRange>? {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
    val n = a.size - prefix - suffix
    val m = b.size - prefix - suffix
    if (n.toLong() * m > MAX_CELLS) return null
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
        lcs[i][j] = if (a[prefix + i] == b[prefix + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val out = ArrayList<EditRange>()
    var i = 0
    var j = 0
    while (i < n || j < m) {
        if (i < n && j < m && a[prefix + i] == b[prefix + j]) { i++; j++; continue }
        val startI = i
        val startJ = j
        while ((i < n || j < m) && !(i < n && j < m && a[prefix + i] == b[prefix + j])) {
            if (j >= m || (i < n && lcs[i + 1][j] >= lcs[i][j + 1])) i++ else j++
        }
        out.add(EditRange(prefix + startI, prefix + i, prefix + startJ, prefix + j))
    }
    return out
}

/**
 * The line edits that turn [old] into [new], or null when they are too different to compare line by line
 * (the whole text is then replaced, as for any other field).
 */
internal fun diffText(old: ByteArray, new: ByteArray): List<TextHunk>? {
    val a = linesOf(asString(old))
    val b = linesOf(asString(new))
    val ranges = diffRanges(a, b) ?: return null
    return ranges.map { r ->
        TextHunk(
            r.aFrom, a.subList(r.aFrom, r.aTo).toList(), b.subList(r.bFrom, r.bTo).toList(),
            a.subList(maxOf(0, r.aFrom - 2), r.aFrom).toList(), r.aTo == a.size,
        )
    }
}

/**
 * Applies [hunks] to [text]. A hunk goes where the lines it removes are, nearest to where they were when the edit was
 * made; if they are no longer there (another plugin changed them) it is a [Conflict].
 */
internal fun applyText(text: ByteArray, hunks: List<TextHunk>, what: String): ByteArray {
    val lines = ArrayList(linesOf(asString(text)))
    var shift = 0
    for (h in hunks) {
        val expected = (h.oldStart + shift).coerceIn(0, lines.size)
        var at = -1
        if (h.removed.isEmpty()) {
            at = expected
            if (h.atEnd) at = lines.size
            else if (h.before.isNotEmpty()) {
                var best = Int.MAX_VALUE
                for (p in h.before.size..lines.size) {
                    if (kotlin.math.abs(p - expected) >= best) continue
                    if (h.before.indices.all { lines[p - h.before.size + it] == h.before[it] }) { at = p; best = kotlin.math.abs(p - expected) }
                }
            }
        }
        else {
            var best = Int.MAX_VALUE
            for (p in 0..lines.size - h.removed.size) {
                if (kotlin.math.abs(p - expected) >= best) continue
                if (h.removed.indices.all { lines[p + it] == h.removed[it] }) { at = p; best = kotlin.math.abs(p - expected) }
            }
        }
        if (at < 0) throw Conflict("cannot edit the text $what: the lines a plugin changed are no longer as it expects")
        repeat(h.removed.size) { lines.removeAt(at) }
        lines.addAll(at, h.added)
        shift += h.added.size - h.removed.size
    }
    return asBytes(lines.joinToString(""))
}
