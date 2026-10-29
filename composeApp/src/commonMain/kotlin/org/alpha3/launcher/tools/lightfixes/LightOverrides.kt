// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.lightfixes

import org.alpha3.launcher.tools.openmw.*

object LightFlags {
    const val DYNAMIC = 0x001
    const val CAN_CARRY = 0x002
    const val NEGATIVE = 0x004
    const val FLICKER = 0x008
    const val FIRE = 0x010
    const val OFF_BY_DEFAULT = 0x020
    const val FLICKER_SLOW = 0x040
    const val PULSE = 0x080
    const val PULSE_SLOW = 0x100

    // FLICKERSLOW and PULSESLOW are older spellings that still appear in documentation.
    private val names = linkedMapOf(
        "DYNAMIC" to DYNAMIC, "CAN_CARRY" to CAN_CARRY, "NEGATIVE" to NEGATIVE, "FLICKER" to FLICKER,
        "FIRE" to FIRE, "OFF_BY_DEFAULT" to OFF_BY_DEFAULT, "FLICKER_SLOW" to FLICKER_SLOW,
        "PULSE" to PULSE, "PULSE_SLOW" to PULSE_SLOW,
    )
    private val aliases = mapOf("FLICKERSLOW" to FLICKER_SLOW, "PULSESLOW" to PULSE_SLOW)

    fun names(flags: Int): List<String> = names.filterValues { flags and it != 0 }.keys.toList()

    /** Parses `NONE`, or flag names joined by `|`. */
    fun parse(tokens: List<String>): Int {
        var flags = 0
        var none = false
        var real = false
        if (tokens.isEmpty()) fail("Unknown light flag: ``")
        for (raw in tokens) {
            val t = raw.trim().uppercase()
            if (t.isEmpty()) fail("Unknown light flag: `$raw`")
            if (t == "NONE") none = true else {
                flags = flags or (names[t] ?: aliases[t] ?: fail("Unknown light flag: `$raw`"))
                real = true
            }
            if (none && real) fail("Light flag `NONE` cannot be combined with `other light flags`")
        }
        return flags
    }

    fun parse(s: String) = parse(s.split('|'))
}

/** An RGB colour in the range of TES3 colour bytes. */
class LightColor(val red: Int, val green: Int, val blue: Int) {
    internal fun toEsp() = intArrayOf(red, green, blue, 0)
}

/** Per-light settings from `[light_overrides]`; fixed values and multipliers of one kind exclude each other. */
class CustomLightData(
    val color: LightColor? = null,
    val redMult: Float? = null,
    val greenMult: Float? = null,
    val blueMult: Float? = null,
    val hue: Int? = null,
    val saturation: Float? = null,
    val value: Float? = null,
    val hueMult: Float? = null,
    val saturationMult: Float? = null,
    val valueMult: Float? = null,
    val radius: Long? = null,
    val radiusMult: Float? = null,
    val duration: Float? = null,
    val durationMult: Float? = null,
    val flag: Int? = null,
) {
    internal fun validate() {
        fun exclusive(a: Any?, b: Any?, an: String, bn: String) {
            if (a != null && b != null) fail("Fields `$an` and `$bn` are mutually exclusive")
        }
        exclusive(radius, radiusMult, "radius", "radius_mult")
        exclusive(duration, durationMult, "duration", "duration_mult")
        exclusive(hue, hueMult, "hue", "hue_mult")
        exclusive(saturation, saturationMult, "saturation", "saturation_mult")
        exclusive(value, valueMult, "value", "value_mult")
        for ((n, v) in listOf("red_mult" to redMult, "green_mult" to greenMult, "blue_mult" to blueMult, "hue_mult" to hueMult,
            "saturation_mult" to saturationMult, "value_mult" to valueMult, "radius_mult" to radiusMult, "duration" to duration,
            "duration_mult" to durationMult, "saturation" to saturation, "value" to value)) {
            if (v != null && !v.isFinite()) fail("$n must be finite")
        }
    }

    internal fun toToml(sb: StringBuilder) {
        color?.let { sb.append("red = ${it.red}\ngreen = ${it.green}\nblue = ${it.blue}\n") }
        fun f(n: String, v: Float?) { if (v != null) sb.append("$n = ${tomlFloat(v)}\n") }
        f("red_mult", redMult); f("green_mult", greenMult); f("blue_mult", blueMult)
        hue?.let { sb.append("hue = $it\n") }
        f("saturation", saturation); f("value", value); f("hue_mult", hueMult)
        f("saturation_mult", saturationMult); f("value_mult", valueMult)
        radius?.let { sb.append("radius = $it\n") }
        f("radius_mult", radiusMult); f("duration", duration); f("duration_mult", durationMult)
        flag?.let { val n = LightFlags.names(it); sb.append(if (n.isEmpty()) "flag = \"NONE\"\n" else "flag = ${tomlString(n.joinToString("|"))}\n") }
    }

    companion object {
        internal fun fromToml(m: Map<String, Any>): CustomLightData {
            val known = setOf("red", "green", "blue", "red_mult", "green_mult", "blue_mult", "hue", "saturation", "value", "hue_mult",
                "saturation_mult", "value_mult", "radius", "radius_mult", "duration", "duration_mult", "flag")
            m.keys.firstOrNull { it !in known }?.let { fail("unknown field `$it`") }
            fun num(k: String): Number? = m[k]?.let { it as? Number ?: fail("`$k` must be a number") }
            fun float(k: String) = num(k)?.toFloat()
            fun u8(k: String) = num(k)?.toLong()?.also { if (it !in 0..255) fail("`$k` must be 0-255") }?.toInt()
            val r = u8("red"); val g = u8("green"); val b = u8("blue")
            val color = when {
                r == null && g == null && b == null -> null
                r != null && g != null && b != null -> LightColor(r, g, b)
                else -> fail("RGB overrides must specify red, green, and blue")
            }
            val flag = when (val f = m["flag"]) {
                null -> null
                is String -> LightFlags.parse(f)
                is List<*> -> LightFlags.parse(f.map { it as? String ?: fail("`flag` entries must be strings") })
                else -> fail("`flag` must be a string or list of strings")
            }
            return CustomLightData(
                color, float("red_mult"), float("green_mult"), float("blue_mult"),
                num("hue")?.toLong()?.also { if (it < 0) fail("`hue` must not be negative") }?.toInt()?.coerceIn(0, 360),
                float("saturation")?.coerceIn(0f, 1f), float("value")?.coerceIn(0f, 1f),
                float("hue_mult"), float("saturation_mult"), float("value_mult"),
                num("radius")?.toLong()?.also { if (it < 0 || it > 0xffffffffL) fail("`radius` out of range") },
                float("radius_mult"), float("duration"), float("duration_mult"), flag,
            ).also { it.validate() }
        }

        /** Parses the `key=value,key=value` form used on the command line. */
        fun parse(s: String): CustomLightData {
            var r: Int? = null; var g: Int? = null; var b: Int? = null
            val f = HashMap<String, Float>()
            var hue: Int? = null; var radius: Long? = null; var flag: Int? = null
            fun float(k: String, v: String): Float = (v.toFloatOrNull() ?: fail("Invalid number for `$k`: $v")).also { if (!it.isFinite()) fail("Invalid number for `$k`: value must be finite") }
            fun unit(k: String, v: String) = float(k, v).coerceIn(0f, 1f)
            fun excl(has: Boolean, a: String, b2: String) { if (has) fail("Key $a is mutually exclusive with $b2") }
            for (pair in s.split(',').filter { it.isNotBlank() }) {
                val eq = pair.indexOf('=')
                if (eq < 0) fail("Expected key=value pair, got: `$pair`")
                val k = pair.substring(0, eq).trim()
                val v = pair.substring(eq + 1).trim()
                when (k) {
                    "radius_mult" -> { excl(radius != null, "radius", k); f[k] = float(k, v) }
                    "hue_mult" -> { excl(hue != null, "hue", k); f[k] = float(k, v) }
                    "saturation_mult" -> { excl("saturation" in f, "saturation", k); f[k] = float(k, v) }
                    "value_mult" -> { excl("value" in f, "value", k); f[k] = float(k, v) }
                    "duration_mult" -> { excl("duration" in f, "duration", k); f[k] = float(k, v) }
                    "red_mult", "green_mult", "blue_mult" -> f[k] = float(k, v)
                    "duration" -> { excl("duration_mult" in f, "duration_mult", k); f[k] = float(k, v) }
                    "radius" -> { excl("radius_mult" in f, "radius_mult", k); radius = v.toLongOrNull()?.takeIf { it in 0..0xffffffffL } ?: fail("Invalid number for `radius`: $v") }
                    "hue" -> { excl("hue_mult" in f, "hue_mult", k); hue = (v.toLongOrNull()?.takeIf { it >= 0 } ?: fail("Invalid number for `hue`: $v")).coerceIn(0, 360).toInt() }
                    "saturation" -> { excl("saturation_mult" in f, "saturation_mult", k); f[k] = unit(k, v) }
                    "value" -> { excl("value_mult" in f, "value_mult", k); f[k] = unit(k, v) }
                    "red" -> r = v.toIntOrNull()?.takeIf { it in 0..255 } ?: fail("Invalid number for `red`: $v")
                    "green" -> g = v.toIntOrNull()?.takeIf { it in 0..255 } ?: fail("Invalid number for `green`: $v")
                    "blue" -> b = v.toIntOrNull()?.takeIf { it in 0..255 } ?: fail("Invalid number for `blue`: $v")
                    "flag" -> flag = LightFlags.parse(v)
                    else -> fail("Unknown field: `$k`")
                }
            }
            val color = when {
                r == null && g == null && b == null -> null
                r != null && g != null && b != null -> LightColor(r, g, b)
                else -> fail("RGB overrides must specify red, green, and blue")
            }
            return CustomLightData(color, f["red_mult"], f["green_mult"], f["blue_mult"], hue, f["saturation"], f["value"],
                f["hue_mult"], f["saturation_mult"], f["value_mult"], radius, f["radius_mult"], f["duration"], f["duration_mult"], flag)
        }
    }
}

/** Per-cell lighting from `[ambient_overrides]`. */
class CustomCellAmbient(
    val ambient: LightColor? = null,
    val sunlight: LightColor? = null,
    val fog: LightColor? = null,
    val fogDensity: Float? = null,
) {
    internal fun toToml(name: String, sb: StringBuilder) {
        for ((n, c) in listOf("ambient" to ambient, "sunlight" to sunlight, "fog" to fog)) {
            if (c != null) sb.append("\n[ambient_overrides.${tomlKey(name)}.$n]\nred = ${c.red}\ngreen = ${c.green}\nblue = ${c.blue}\n")
        }
        fogDensity?.let { sb.append("\n[ambient_overrides.${tomlKey(name)}]\nfog_density = ${tomlFloat(it)}\n") }
    }

    companion object {
        private fun color(m: Any?, field: String): LightColor? {
            val t = m as? Map<*, *> ?: return if (m == null) null else fail("`$field` must be a table")
            fun u8(k: String) = (t[k] as? Number)?.toLong()?.also { if (it !in 0..255) fail("`$k` must be 0-255") }?.toInt()
            val rgb = listOf(u8("red"), u8("green"), u8("blue"))
            val legacy = listOf(t["hue"], t["saturation"], t["value"])
            val hasRgb = rgb.any { it != null }
            val hasHsv = legacy.any { it != null }
            if (hasRgb && hasHsv) fail("RGB color fields are mutually exclusive with legacy HSV color fields")
            if (hasRgb) {
                if (rgb.any { it == null }) fail("RGB overrides must specify red, green, and blue")
                return LightColor(rgb[0]!!, rgb[1]!!, rgb[2]!!)
            }
            if (hasHsv) {
                if (legacy.any { it !is Number }) fail("HSV ambient colors must specify hue, saturation, and value")
                val c = hsvDegreesToRgb8((legacy[0] as Number).toInt(), (legacy[1] as Number).toFloat(), (legacy[2] as Number).toFloat())
                return LightColor(c[0], c[1], c[2])
            }
            fail("RGB colors must specify red, green, and blue")
        }

        internal fun fromToml(m: Map<String, Any>): CustomCellAmbient {
            m.keys.firstOrNull { it !in setOf("ambient", "sunlight", "fog", "fog_density") }?.let { fail("unknown field `$it`") }
            return CustomCellAmbient(color(m["ambient"], "ambient"), color(m["sunlight"], "sunlight"), color(m["fog"], "fog"), (m["fog_density"] as? Number)?.toFloat())
        }

        /** Parses `ambient=red=1,green=2,blue=3;fog_density=0.5` as used on the command line. */
        fun parse(s: String): CustomCellAmbient {
            var ambient: LightColor? = null; var sunlight: LightColor? = null; var fog: LightColor? = null; var density: Float? = null
            fun rgb(field: String, v: String): LightColor {
                var r: Int? = null; var g: Int? = null; var b: Int? = null
                for (pair in v.split(',').filter { it.isNotBlank() }) {
                    val eq = pair.indexOf('=')
                    if (eq < 0) fail("Invalid color for `$field`: Expected key=value pair, got: `$pair`")
                    val n = pair.substring(eq + 1).trim().toIntOrNull()?.takeIf { it in 0..255 } ?: fail("Invalid color for `$field`: bad value in `$pair`")
                    when (val k = pair.substring(0, eq).trim()) {
                        "red" -> r = n; "green" -> g = n; "blue" -> b = n
                        else -> fail("Invalid color for `$field`: Unknown field: `$k`")
                    }
                }
                return LightColor(r ?: fail("Invalid color for `$field`: Missing required field: `red`"), g ?: fail("Invalid color for `$field`: Missing required field: `green`"), b ?: fail("Invalid color for `$field`: Missing required field: `blue`"))
            }
            for (pair in s.split(';').filter { it.isNotBlank() }) {
                val eq = pair.indexOf('=')
                if (eq < 0) fail("Expected key=value pair, got: `$pair`")
                val v = pair.substring(eq + 1)
                when (val k = pair.substring(0, eq).trim()) {
                    "ambient" -> ambient = rgb(k, v)
                    "sunlight" -> sunlight = rgb(k, v)
                    "fog" -> fog = rgb(k, v)
                    "fog_density" -> density = v.toFloatOrNull() ?: fail("Invalid color for `fog_density`: $v")
                    else -> fail("Unknown field: `$k`")
                }
            }
            return CustomCellAmbient(ambient, sunlight, fog, density)
        }
    }
}

internal fun tomlFloat(f: Float): String = f.toString().let { if (it.contains('.') || it.contains('E') || it.contains("N")) it else "$it.0" }

internal fun tomlString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

internal fun tomlKey(k: String): String =
    if (k.isNotEmpty() && k.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) k else tomlString(k)
