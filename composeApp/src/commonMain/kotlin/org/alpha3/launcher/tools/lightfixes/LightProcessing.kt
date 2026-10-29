// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.lightfixes

import org.alpha3.launcher.tools.openmw.*

/** The LHDT subrecord of a light: what lightfixes rewrites. */
internal class LightData(
    val weight: Float,
    val value: Long,
    var time: Int,
    var radius: Long,
    var color: IntArray,
    var flags: Int,
) {
    fun copy() = LightData(weight, value, time, radius, color.copyOf(), flags)

    companion object {
        fun parse(d: ByteArray): LightData? {
            if (d.size < 24) return null
            fun u32(o: Int) = (d[o].toLong() and 0xff) or ((d[o + 1].toLong() and 0xff) shl 8) or ((d[o + 2].toLong() and 0xff) shl 16) or ((d[o + 3].toLong() and 0xff) shl 24)
            return LightData(
                Float.fromBits(u32(0).toInt()), u32(4), u32(8).toInt(), u32(12),
                intArrayOf(d[16].toInt() and 0xff, d[17].toInt() and 0xff, d[18].toInt() and 0xff, d[19].toInt() and 0xff), u32(20).toInt(),
            )
        }
    }

    fun toBytes(original: ByteArray): ByteArray {
        val out = original.copyOf()
        fun put(o: Int, v: Long) { for (i in 0 until 4) out[o + i] = (v ushr (8 * i)).toByte() }
        put(8, time.toLong() and 0xffffffffL); put(12, radius)
        for (i in 0 until 4) out[16 + i] = color[i].toByte()
        put(20, flags.toLong() and 0xffffffffL)
        return out
    }
}

private fun scaledU32(value: Long, mult: Float): Long {
    val f = maxOf(value.toFloat() * mult, 0f)
    return if (f >= 4294967296f) 0xffffffffL else f.toLong()
}

private fun scaledI32(value: Int, mult: Float): Int = (value.toFloat() * mult).toInt()

private fun scaledU8(value: Int, mult: Float): Int = (value.toFloat() * mult).coerceIn(0f, 255f).toInt()

/** Lights whose hue falls outside the orange range count as coloured and get their own multipliers. */
internal fun lightToHsv(d: LightData): Pair<Hsv, Boolean> {
    val hsv = colorToHsv(d.color[0], d.color[1], d.color[2])
    val hue = hsv.positiveHue
    return hsv to !(hue >= 14f && hue <= 64f)
}

private fun applyHsvReplacement(hsv: Hsv, r: CustomLightData, gh: Float, gs: Float, gv: Float, useGlobal: Boolean) {
    when {
        r.hueMult != null -> hsv.hue = hsv.hue * r.hueMult
        r.hue != null -> hsv.hue = r.hue.coerceIn(0, 360).toFloat()
        useGlobal -> hsv.hue = hsv.hue * gh
    }
    when {
        r.saturationMult != null -> hsv.saturation *= r.saturationMult
        r.saturation != null -> hsv.saturation = r.saturation
        useGlobal -> hsv.saturation *= gs
    }
    when {
        r.valueMult != null -> hsv.value *= r.valueMult
        r.value != null -> hsv.value = r.value
        useGlobal -> hsv.value *= gv
    }
}

internal fun lightChanges(old: LightData, new: LightData): List<String> {
    val out = ArrayList<String>()
    if (!old.color.contentEquals(new.color)) out.add("color ${old.color.toList()} -> ${new.color.toList()}")
    if (old.radius != new.radius) out.add("radius ${old.radius} -> ${new.radius}")
    if (old.time != new.time) out.add("duration ${old.time} -> ${new.time}")
    if (old.flags != new.flags) out.add("flags ${LightFlags.names(old.flags)} -> ${LightFlags.names(new.flags)}")
    return out
}

/** Applies the configured fixes to [light] in place and describes what changed. */
internal fun processLight(config: CompiledLightFixes, id: String, light: LightData): List<String> {
    val c = config.config
    val original = light.copy()

    if (c.disableNegativeLights && light.flags and LightFlags.NEGATIVE != 0) {
        light.flags = light.flags and LightFlags.NEGATIVE.inv()
        light.radius = 0
        light.color = intArrayOf(0, 0, 0, 0)
        return lightChanges(original, light)
    }
    if (c.disableFlickering) light.flags = light.flags and (LightFlags.FLICKER or LightFlags.FLICKER_SLOW).inv()
    if (c.disablePulse) light.flags = light.flags and (LightFlags.PULSE or LightFlags.PULSE_SLOW).inv()

    var (hsv, colored) = lightToHsv(light)
    val replacement = config.lightRegexes.firstOrNull { it.first.containsMatchIn(id.lowercase()) }?.second
    val gRadius = if (colored) c.coloredRadius else c.standardRadius
    val gHue = if (colored) c.coloredHue else c.standardHue
    val gSat = if (colored) c.coloredSaturation else c.standardSaturation
    val gVal = if (colored) c.coloredValue else c.standardValue

    if (replacement != null) {
        val useGlobal = replacement.color == null
        replacement.color?.let { hsv = colorToHsv(it.red, it.green, it.blue) }
        applyHsvReplacement(hsv, replacement, gHue, gSat, gVal, useGlobal)

        // An infinite light's -1 means "never burns out", not a time to multiply.
        when {
            replacement.durationMult != null -> if (light.time != -1) light.time = scaledI32(light.time, replacement.durationMult)
            replacement.duration != null -> light.time = replacement.duration.toInt()
            light.time != -1 -> light.time = scaledI32(light.time, c.durationMult)
        }
        light.radius = when {
            replacement.radiusMult != null -> scaledU32(light.radius, replacement.radiusMult)
            replacement.radius != null -> replacement.radius
            else -> scaledU32(light.radius, gRadius)
        }
        replacement.flag?.let { light.flags = it }
    } else {
        hsv.hue = hsv.hue * gHue
        hsv.saturation *= gSat
        hsv.value *= gVal
        light.radius = scaledU32(light.radius, gRadius)
        if (light.time != -1) light.time = scaledI32(light.time, c.durationMult)
    }

    val rgb = hsvToRgb8(hsv)
    light.color = intArrayOf(rgb[0], rgb[1], rgb[2], 0)
    if (replacement != null) {
        replacement.redMult?.let { light.color[0] = scaledU8(light.color[0], it) }
        replacement.greenMult?.let { light.color[1] = scaledU8(light.color[1], it) }
        replacement.blueMult?.let { light.color[2] = scaledU8(light.color[2], it) }
    }
    return lightChanges(original, light)
}

/** A [LightFixesConfig] with its patterns compiled (case-insensitive, matched anywhere, like Rust's regex). */
internal class CompiledLightFixes(val config: LightFixesConfig) {
    val excludedIds: List<Regex>
    val excludedPlugins: List<Regex>
    val lightRegexes: List<Pair<Regex, CustomLightData>>
    val ambientRegexes: List<Pair<Regex, CustomCellAmbient>>

    init {
        val errors = ArrayList<String>()
        fun compile(kind: String, p: String): Regex? = try {
            Regex(p, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            errors.add("Couldn't compile $kind regex: $p: ${e.message}"); null
        }
        excludedIds = config.excludedIds.mapNotNull { compile("excluded id", it) }
        excludedPlugins = config.excludedPlugins.mapNotNull { compile("excluded plugin", it) }
        lightRegexes = config.lightOverrides.mapNotNull { (k, v) -> compile("light override", k)?.let { it to v } }
        ambientRegexes = config.ambientOverrides.mapNotNull { (k, v) -> compile("ambient override", k)?.let { it to v } }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    fun isExcludedId(id: String) = excludedIds.any { it.containsMatchIn(id) }
    fun isExcludedPlugin(fileName: String) = excludedPlugins.any { it.containsMatchIn(fileName) }
}
