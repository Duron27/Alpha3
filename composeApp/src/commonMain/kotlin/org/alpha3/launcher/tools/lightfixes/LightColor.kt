// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of S3LightFixes (https://github.com/DreamWeave-MP/S3LightFixes), MIT licensed.
// The colour maths follows the Rust `palette` crate in single precision, step for step, so that
// the same inputs round to the same bytes.
package org.alpha3.launcher.tools.lightfixes

import kotlin.math.abs
import kotlin.math.floor

/** HSV with the hue kept in raw (unnormalised) degrees, as `palette::Hsv` keeps it. */
internal class Hsv(var hue: Float, var saturation: Float, var value: Float) {
    val positiveHue get() = normalizeDegrees(hue)
}

internal fun normalizeDegrees(deg: Float): Float = deg - floor(deg / 360f) * 360f

private val RECIP_255 = 1f / 255f
private const val C23 = 0x4b000000

internal fun colorToHsv(r: Int, g: Int, b: Int): Hsv {
    val red = r.toFloat() * RECIP_255
    val green = g.toFloat() * RECIP_255
    val blue = b.toFloat() * RECIP_255
    var max: Float
    var min: Float
    var sep: Float
    var coeff: Float
    if (red > green) { max = red; min = green; sep = green - blue; coeff = 0f } else { max = green; min = red; sep = blue - red; coeff = 2f }
    if (blue > max) {
        max = blue; sep = red - green; coeff = 4f
    } else if (blue < min) {
        min = blue
    }
    var h = 0f
    var s = 0f
    if (max != min) {
        val d = max - min
        h = (sep / d + coeff) * 60f
        s = d / max
    }
    return Hsv(h, s.coerceIn(0f, 1f), max.coerceIn(0f, 1f))
}

/** `Hsv -> Srgb<u8>`: convert, clamp to 0..1, then round to the nearest byte. */
internal fun hsvToRgb8(hsv: Hsv): IntArray {
    val c = hsv.value * hsv.saturation
    val h = hsv.positiveHue / 60f
    val hMod2 = h - floor(h * 0.5f) * 2f
    val x = c * (1f - abs(hMod2 - 1f))
    val m = hsv.value - c
    val zone0 = h >= 0f && h < 1f
    val zone1 = h >= 1f && h < 2f
    val zone2 = h >= 2f && h < 3f
    val zone3 = h >= 3f && h < 4f
    val zone4 = h >= 4f && h < 5f
    val red = if (zone1 || zone4) x else if (zone2 || zone3) 0f else c
    val green = if (zone0 || zone3) x else if (zone1 || zone2) c else 0f
    val blue = if (zone0 || zone1) 0f else if (zone3 || zone4) c else x
    return intArrayOf(toByte(red + m), toByte(green + m), toByte(blue + m))
}

private fun toByte(component: Float): Int {
    val clamped = component.coerceIn(0f, 1f)
    val scaled = minOf(clamped * 255f, 255f)
    val f = scaled + Float.fromBits(C23)
    return maxOf(f.toRawBits() - C23, 0)
}

/** Hue in degrees (clamped to 0..360), saturation and value in 0..1 to an RGB byte colour. */
internal fun hsvDegreesToRgb8(hue: Int, saturation: Float, value: Float): IntArray =
    hsvToRgb8(Hsv(hue.coerceIn(0, 360).toFloat(), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))
