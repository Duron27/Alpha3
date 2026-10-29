package org.alpha3.launcher.utils

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** Fixed number of decimals, like String.format("%.Nf", value) (which is not available in common code). */
fun formatDecimals(value: Double, digits: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    val factor = 10.0.pow(digits)
    val scaled = (abs(value) * factor).roundToLong()
    val integer = scaled / factor.toLong()
    val fraction = (scaled % factor.toLong()).toString().padStart(digits, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (digits == 0) "$sign$integer" else "$sign$integer.$fraction"
}

fun formatDecimals(value: Float, digits: Int): String = formatDecimals(value.toDouble(), digits)
