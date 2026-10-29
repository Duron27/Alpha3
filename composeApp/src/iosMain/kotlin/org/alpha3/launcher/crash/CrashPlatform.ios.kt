package org.alpha3.launcher.crash

import org.alpha3.launcher.utils.currentTimeMillis

// No zip writer on iOS yet: the viewer reports the export as failed
actual fun writeZip(destinationPath: String, entries: List<ZipSource>): Boolean = false

/** UTC, from days since the epoch (the civil-from-days algorithm). */
actual fun fileTimestamp(): String {
    val millis = currentTimeMillis()
    val days = millis.floorDiv(86_400_000L)
    val seconds = millis.mod(86_400_000L) / 1000
    val z = days + 719468
    val era = z.floorDiv(146097L)
    val dayOfEra = z - era * 146097
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPart = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPart + 2) / 5 + 1
    val month = if (monthPart < 10) monthPart + 3 else monthPart - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    fun two(value: Long) = value.toString().padStart(2, '0')
    return "$year-${two(month)}-${two(day)}_${two(seconds / 3600)}-${two(seconds % 3600 / 60)}-${two(seconds % 60)}"
}
