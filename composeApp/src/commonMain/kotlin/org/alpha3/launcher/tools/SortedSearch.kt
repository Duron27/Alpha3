package org.alpha3.launcher.tools

// binarySearch on primitive arrays is JVM-only in the stdlib; same contract (-(insertion point) - 1 when absent).

fun FloatArray.sortedIndexOf(key: Float): Int {
    var lo = 0; var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val c = this[mid].compareTo(key)
        if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
    }
    return -(lo + 1)
}

fun LongArray.sortedIndexOf(key: Long): Int {
    var lo = 0; var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid]
        if (v < key) lo = mid + 1 else if (v > key) hi = mid - 1 else return mid
    }
    return -(lo + 1)
}
