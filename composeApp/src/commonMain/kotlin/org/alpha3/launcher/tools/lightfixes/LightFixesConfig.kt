// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.lightfixes

import org.alpha3.launcher.tools.openmw.*

const val LIGHT_CONFIG_NAME = "lightconfig.toml"
const val LIGHT_LOG_NAME = "alpha3_lightfixes.log"
const val LIGHTFIXES_PLUGIN_NAME = "alpha3_lightfixes.omwaddon"

/** Settings from `lightconfig.toml`; the defaults are S3LightFixes' own. */
class LightFixesConfig {
    var disableInteriorSun = false
    var disableFlickering = true
    var disablePulse = false
    var disableNegativeLights = true
    var autoEnable = false
    var standardHue = 0.62f
    var standardSaturation = 0.8f
    var standardValue = 0.57f
    var standardRadius = 1.2f
    var coloredHue = 1.0f
    var coloredSaturation = 0.9f
    var coloredValue = 0.7f
    var coloredRadius = 1.1f
    var durationMult = 2.5f
    var excludedPlugins: MutableList<String> = DEFAULT_EXCLUDED_PLUGINS.toMutableList()
    var excludedIds: MutableList<String> = ArrayList()
    var lightOverrides: LinkedHashMap<String, CustomLightData> = LinkedHashMap()
    var ambientOverrides: LinkedHashMap<String, CustomCellAmbient> = LinkedHashMap()
    var outputDir: String? = null
    var saveConfig = false

    /** Settings in the file this version does not read (misspelled, or under the wrong table). */
    var unknownKeys: List<String> = emptyList()
        private set

    /** Serialises to the `lightconfig.toml` format. */
    fun toToml(): String {
        val sb = StringBuilder()
        fun b(n: String, v: Boolean) { sb.append("$n = $v\n") }
        fun f(n: String, v: Float) { sb.append("$n = ${tomlFloat(v)}\n") }
        fun list(n: String, v: List<String>) {
            if (v.isEmpty()) sb.append("$n = []\n") else sb.append("$n = [\n").also { v.forEach { s -> sb.append("    ${tomlString(s)},\n") } }.append("]\n")
        }
        b("disable_interior_sun", disableInteriorSun); b("disable_flickering", disableFlickering); b("disable_pulse", disablePulse)
        b("disable_negative_lights", disableNegativeLights); b("auto_enable", autoEnable)
        f("standard_hue", standardHue); f("standard_saturation", standardSaturation); f("standard_value", standardValue); f("standard_radius", standardRadius)
        f("colored_hue", coloredHue); f("colored_saturation", coloredSaturation); f("colored_value", coloredValue); f("colored_radius", coloredRadius)
        f("duration_mult", durationMult)
        list("excluded_plugins", excludedPlugins); list("excluded_ids", excludedIds)
        outputDir?.let { sb.append("output_dir = ${tomlString(it)}\n") }
        b("save_config", saveConfig)
        for ((k, v) in lightOverrides) { sb.append("\n[light_overrides.${tomlKey(k)}]\n"); v.toToml(sb) }
        for ((k, v) in ambientOverrides) v.toToml(k, sb)
        return sb.toString()
    }

    companion object {
        val DEFAULT_EXCLUDED_PLUGINS = listOf(
            "alpha3_deleted_groundcover.omwaddon", "deleted_groundcover.omwaddon", // moved reference that cannot be resolved
            "Clean_Argonian Full Helms Lore Integrated.ESP", // unexpected CELL::FLTV
            "Baldurwind.omwaddon", "Crassified Navigation.omwaddon", "LuaMultiMark.omwaddon", "S3maphore.esp", "Toolgun.omwaddon", // LUAL
        )

        // Settings older versions wrote that no longer do anything.
        private val retiredKeys = setOf("auto_install", "save_log")
        private val knownKeys = setOf(
            "disable_interior_sun", "disable_flickering", "disable_pulse", "disable_negative_lights", "auto_enable", "no_notifications",
            "debug", "dry_run", "validate_config", "standard_hue", "standard_saturation", "standard_value", "standard_radius",
            "colored_hue", "colored_saturation", "colored_value", "colored_radius", "duration_mult", "excluded_plugins", "excluded_ids",
            "light_overrides", "ambient_overrides", "output_dir", "save_config",
        )

        fun fromToml(text: String): LightFixesConfig {
            val t = Toml.parse(text)
            val c = LightFixesConfig()
            fun bool(k: String, d: Boolean) = (t[k] as? Boolean) ?: if (k in t) fail("`$k` must be true or false") else d
            fun float(k: String, d: Float) = (t[k] as? Number)?.toFloat() ?: if (k in t) fail("`$k` must be a number") else d
            fun strings(k: String, d: List<String>) = when (val v = t[k]) {
                null -> d
                is List<*> -> v.map { it as? String ?: fail("`$k` must be a list of strings") }
                else -> fail("`$k` must be a list of strings")
            }
            c.disableInteriorSun = bool("disable_interior_sun", false)
            c.disableFlickering = bool("disable_flickering", true)
            c.disablePulse = bool("disable_pulse", false)
            c.disableNegativeLights = bool("disable_negative_lights", true)
            c.autoEnable = bool("auto_enable", false)
            c.standardHue = float("standard_hue", 0.62f); c.standardSaturation = float("standard_saturation", 0.8f)
            c.standardValue = float("standard_value", 0.57f); c.standardRadius = float("standard_radius", 1.2f)
            c.coloredHue = float("colored_hue", 1.0f); c.coloredSaturation = float("colored_saturation", 0.9f)
            c.coloredValue = float("colored_value", 0.7f); c.coloredRadius = float("colored_radius", 1.1f)
            c.durationMult = float("duration_mult", 2.5f)
            c.excludedPlugins = strings("excluded_plugins", DEFAULT_EXCLUDED_PLUGINS).toMutableList()
            c.excludedIds = strings("excluded_ids", emptyList()).toMutableList()
            c.outputDir = t["output_dir"] as? String
            c.saveConfig = bool("save_config", false)
            for ((name, v) in (t["light_overrides"] as? Map<*, *> ?: emptyMap<String, Any>())) {
                @Suppress("UNCHECKED_CAST")
                c.lightOverrides[name as String] = try { CustomLightData.fromToml(v as? Map<String, Any> ?: fail("must be a table")) } catch (e: ToolException) { fail("light_overrides.$name: ${e.message}") }
            }
            for ((name, v) in (t["ambient_overrides"] as? Map<*, *> ?: emptyMap<String, Any>())) {
                @Suppress("UNCHECKED_CAST")
                c.ambientOverrides[name as String] = try { CustomCellAmbient.fromToml(v as? Map<String, Any> ?: fail("must be a table")) } catch (e: ToolException) { fail("ambient_overrides.$name: ${e.message}") }
            }
            c.unknownKeys = t.keys.filter { it !in knownKeys && it !in retiredKeys }
            return c
        }
    }
}

/** Command-line style overrides, applied over `lightconfig.toml`. Unset (null) values leave the file's setting alone. */
class LightFixesArgs(
    val standardHue: Float? = null,
    val standardSaturation: Float? = null,
    val standardValue: Float? = null,
    val standardRadius: Float? = null,
    val coloredHue: Float? = null,
    val coloredSaturation: Float? = null,
    val coloredValue: Float? = null,
    val coloredRadius: Float? = null,
    val durationMult: Float? = null,
    val disableFlickering: Boolean? = null,
    val disablePulse: Boolean? = null,
    val disableNegativeLights: Boolean? = null,
    val autoEnable: Boolean = false,
    /** Merged onto the file's lists. */
    val excludedIds: List<String> = emptyList(),
    val excludedPlugins: List<String> = emptyList(),
    val lightOverrides: List<Pair<String, CustomLightData>> = emptyList(),
    val ambientOverrides: List<Pair<String, CustomCellAmbient>> = emptyList(),
    /** Classic mode for vtastek's OpenMW 0.47 shaders: no sunlight in interiors. */
    val classic: Boolean = false,
    /** Write lightconfig.toml even if it exists. */
    val updateConfig: Boolean = false,
) {
    internal fun applyTo(c: LightFixesConfig) {
        standardHue?.let { c.standardHue = it }; standardSaturation?.let { c.standardSaturation = it }
        standardValue?.let { c.standardValue = it }; standardRadius?.let { c.standardRadius = it }
        coloredHue?.let { c.coloredHue = it }; coloredSaturation?.let { c.coloredSaturation = it }
        coloredValue?.let { c.coloredValue = it }; coloredRadius?.let { c.coloredRadius = it }
        durationMult?.let { c.durationMult = it }
        disableFlickering?.let { c.disableFlickering = it }; disablePulse?.let { c.disablePulse = it }
        disableNegativeLights?.let { c.disableNegativeLights = it }
        if (autoEnable) c.autoEnable = true
        c.excludedIds.addAll(excludedIds); c.excludedPlugins.addAll(excludedPlugins)
        for ((k, v) in lightOverrides) c.lightOverrides[k] = v
        for ((k, v) in ambientOverrides) c.ambientOverrides[k] = v
        if (classic) c.disableInteriorSun = true
    }
}
