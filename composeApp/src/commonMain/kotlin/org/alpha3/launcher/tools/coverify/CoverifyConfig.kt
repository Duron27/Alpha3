// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.coverify

import org.alpha3.launcher.tools.openmw.*

/** Settings from groundcoverify.toml; the defaults mirror GOCoverify's built-in template. */
class CoverifyConfig(
    val outputDirectory: String = ".",
    val groundcoverOutput: String = "alpha3_groundcover.omwaddon",
    val deletedOutput: String = "alpha3_deleted_groundcover.omwaddon",
    val grassIds: List<String> = emptyList(),
    val objectTypes: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val openMWConfig: String = "",
    val ignoredPlugins: List<String> = DEFAULT_IGNORED_PLUGINS,
) {
    fun validate() {
        for (name in listOf(groundcoverOutput, deletedOutput)) {
            if (name.equals(MANIFEST_NAME, true) || name.equals("meshes", true)) fail("reserved output filename \"$name\"")
            if (name.isEmpty() || name == "." || name == ".." || name.any { it == '/' || it == '\\' }) {
                fail("output plugin must be a filename, got \"$name\"")
            }
        }
        if (groundcoverOutput.equals(deletedOutput, true)) fail("output plugin names must be different")
    }

    companion object {
        val DEFAULT_IGNORED_PLUGINS = listOf(
            "alpha3_merged.omwaddon", "alpha3_leveled_lists.omwaddon", "alpha3_groundcover.omwaddon", "alpha3_deleted_groundcover.omwaddon", "alpha3_lightfixes.omwaddon",
            // Output of the desktop versions of these tools, which may also be in the load order.
            "delta-merged.omwaddon", "groundcover.omwaddon", "deleted_groundcover.omwaddon",
            "S3LightFixes.omwaddon", "OMWLLFMod.omwaddon", "merged.omwaddon", "Merged Objects.esp",
        )

        val DEFAULT = CoverifyConfig(
            grassIds = listOf(
                "grass", "kelp", "lilypad", "fern",
                // spartium and thirrlily are activators, not statics
                "thirrlily", "spartium", "in_cave_plant", "reedgroup", "t_mw_floratv_treezifa",
                "t_mw_florash_bush", "t_mw_floraow_varga", "t_cyr_floragc_shrub", "t_cyr_floragc_bush_02",
                "t_glb_flora_cattails", "t_cyr_florastr_shrub", "flora_bm_shrub",
            ),
            objectTypes = listOf("Static"),
            exclude = listOf(
                "refernce", "infernace", // typos caught by 'fern'
                "planter", // may contain grass (AB_Furn_ImpPlanterGrass)
                "_furn_", "_skelp", "t_glb_var_skeleton",
                "cliffgrass", // grassy rock terrace
                "terr", "grassplane", "flora_s_m_10_grass", "cave_mud_rocks_fern", "ab_in_cavemold",
                "rp_mh_rock", "ex_cave_grass00", "secret_fern", "flora_grass_entrance",
            ),
        )

        /**
         * Parses a groundcoverify.toml. As in GOCoverify, keys the file omits keep their standalone
         * defaults (empty match lists, default ignored plugins), not the built-in template's lists.
         */
        fun fromToml(text: String): CoverifyConfig {
            val t = MiniToml.parse(text)
            fun str(k: String, d: String) = (t[k] as? String) ?: d
            fun list(k: String, d: List<String>) = (t[k] as? List<*>)?.map { it as String } ?: d
            return CoverifyConfig(
                outputDirectory = str("output_directory", "."),
                groundcoverOutput = str("groundcover_output", "alpha3_groundcover.omwaddon"),
                deletedOutput = str("deleted_output", "alpha3_deleted_groundcover.omwaddon"),
                grassIds = list("grass_ids", emptyList()),
                objectTypes = list("object_types", emptyList()),
                exclude = list("exclude", emptyList()),
                openMWConfig = str("openmw_config", ""),
                ignoredPlugins = list("ignored_plugins", DEFAULT_IGNORED_PLUGINS),
            ).also { it.validate() }
        }
    }
}

internal const val MANIFEST_NAME = "gocoverify-manifest.json"

/** Just enough TOML for groundcoverify.toml: top-level keys with string or string-array values. */
internal object MiniToml {
    fun parse(text: String): Map<String, Any> {
        val out = HashMap<String, Any>()
        var i = 0
        val n = text.length

        fun skipBlank(newlines: Boolean) {
            while (i < n) {
                val c = text[i]
                when {
                    c == '#' -> while (i < n && text[i] != '\n') i++
                    c == '\n' && !newlines -> return
                    c.isWhitespace() -> i++
                    else -> return
                }
            }
        }

        fun string(): String {
            val q = text[i++]
            if (text.startsWith("$q$q", i)) fail("multi-line TOML strings are not supported")
            val sb = StringBuilder()
            while (true) {
                if (i >= n || text[i] == '\n') fail("unterminated TOML string")
                val c = text[i++]
                if (c == q) return sb.toString()
                if (q == '"' && c == '\\') {
                    when (val e = text[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        '"', '\\' -> sb.append(e)
                        'u' -> { sb.append(text.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> fail("unsupported TOML escape \\$e")
                    }
                } else sb.append(c)
            }
        }

        fun value(): Any {
            skipBlank(false)
            if (i >= n) fail("missing TOML value")
            return when (text[i]) {
                '"', '\'' -> string()
                '[' -> {
                    i++
                    val items = ArrayList<Any>()
                    while (true) {
                        skipBlank(true)
                        if (i >= n) fail("unterminated TOML array")
                        if (text[i] == ']') { i++; return items }
                        items.add(value())
                        skipBlank(true)
                        if (i < n && text[i] == ',') i++
                    }
                    @Suppress("UNREACHABLE_CODE") items
                }
                else -> { // bare scalar: ignored, but must be consumed
                    val s = i
                    while (i < n && text[i] != '\n' && text[i] != '#' && text[i] != ',' && text[i] != ']') i++
                    text.substring(s, i).trim()
                }
            }
        }

        while (true) {
            skipBlank(true)
            if (i >= n) break
            if (text[i] == '[') { while (i < n && text[i] != '\n') i++; continue } // table header
            val s = i
            while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
            val key = text.substring(s, i)
            if (key.isEmpty()) fail("unexpected TOML character '${text[i]}'")
            skipBlank(false)
            if (i >= n || text[i] != '=') fail("expected '=' after TOML key $key")
            i++
            out[key] = value()
        }
        return out
    }
}

private val objectTags = mapOf(
    "Static" to "STAT", "Activator" to "ACTI", "Container" to "CONT", "Door" to "DOOR",
    "Light" to "LIGH", "MiscItem" to "MISC", "Misc" to "MISC", "Ingredient" to "INGR",
    "Weapon" to "WEAP", "Armour" to "ARMO", "Armor" to "ARMO", "Clothing" to "CLOT",
    "Book" to "BOOK", "Alchemy" to "ALCH", "Potion" to "ALCH", "Apparatus" to "APPA",
    "Lockpick" to "LOCK", "Probe" to "PROB", "Repair" to "REPA", "Creature" to "CREA", "Npc" to "NPC_",
)

internal class Matcher(c: CoverifyConfig) {
    val types = HashSet<String>()
    private val include: Regex?
    private val exclude: Regex?

    init {
        for (t in c.objectTypes) types.add(objectTags[t] ?: fail("unsupported object type \"$t\""))
        fun compile(patterns: List<String>, name: String): Regex? {
            if (patterns.isEmpty()) return null
            return try {
                Regex(patterns.joinToString("|") { "(?:$it)" })
            } catch (e: Exception) {
                fail("$name: invalid regular expression: ${e.message}")
            }
        }
        include = compile(c.grassIds, "grass_ids")
        exclude = compile(c.exclude, "exclude")
    }

    fun matches(id: String): Boolean =
        include != null && include.containsMatchIn(id) && (exclude == null || !exclude.containsMatchIn(id))
}
