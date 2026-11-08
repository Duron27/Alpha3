package org.alpha3.launcher.cs.model

/**
 * What the Construction Set knows about each record type: its subrecords, in the order the game writes them, and the
 * fields inside each. Written from the TES3 format as OpenMW reads it (components/esm3/load*.cpp). A subrecord that is
 * not listed, or whose size does not match its layout, is still kept and saved byte for byte; the editor shows it raw.
 */
sealed class FieldType(val size: Int) {
    /** The whole subrecord as text, ending at the first NUL (a NUL is written back if the original had one). */
    data object ZString : FieldType(-1)
    /** The whole subrecord as text, no terminator (GMST STRV, descriptions, book text). */
    data object Text : FieldType(-1)
    /** Text in [n] bytes, padded with NULs. */
    class Fixed(n: Int) : FieldType(n)
    data object I8 : FieldType(1)
    data object U8 : FieldType(1)
    data object I16 : FieldType(2)
    data object U16 : FieldType(2)
    data object I32 : FieldType(4)
    data object U32 : FieldType(4)
    data object I64 : FieldType(8)
    data object F32 : FieldType(4)
    /** Bytes the format reserves or nobody understands: kept as they are, shown as hex. */
    class Bytes(n: Int) : FieldType(n)
}

/** One field: its name, type and what its number means (an enum, flags) or which records it names. */
class FieldDef(
    val name: String,
    val type: FieldType,
    val enum: List<String>? = null,
    val flags: Map<Long, String>? = null,
    /** Record types whose ids this field holds (a reference picker in the editor). */
    val refs: Set<String>? = null,
    /** An enum's first value (MagicEffect -1 = none and so on). */
    val enumBase: Int = 0,
    /** The stored value of each enum entry when they are not consecutive (a global's type is 's', 'l' or 'f'). */
    val enumCodes: List<Long>? = null,
)

/** A subrecord: its tag, a label, its fields (by size when one tag has several layouts) and whether it repeats. */
class SubDef(
    val tag: String,
    val label: String,
    val layouts: List<List<FieldDef>>,
    val repeats: Boolean = false,
    val required: Boolean = false,
) {
    /** The layout for a subrecord of [size] bytes, or null (shown raw). */
    fun layoutFor(size: Int): List<FieldDef>? = layouts.firstOrNull { l ->
        val fixed = l.sumOf { maxOf(it.type.size, 0) }
        if (l.any { it.type.size < 0 }) true else fixed == size
    }
}

class RecordDef(val tag: String, val label: String, val subs: List<SubDef>) {
    fun sub(tag: String): SubDef? = subs.firstOrNull { it.tag == tag }
}

// ---- the vocabulary ----------------------------------------------------------------------------------------------------

val ATTRIBUTES = listOf("Strength", "Intelligence", "Willpower", "Agility", "Speed", "Endurance", "Personality", "Luck")
val SKILLS = listOf(
    "Block", "Armorer", "Medium Armor", "Heavy Armor", "Blunt Weapon", "Long Blade", "Axe", "Spear", "Athletics",
    "Enchant", "Destruction", "Alteration", "Illusion", "Conjuration", "Mysticism", "Restoration", "Alchemy",
    "Unarmored", "Security", "Sneak", "Acrobatics", "Light Armor", "Short Blade", "Marksman", "Mercantile",
    "Speechcraft", "Hand-to-hand",
)
val SPECIALIZATIONS = listOf("Combat", "Magic", "Stealth")
val SCHOOLS = listOf("Alteration", "Conjuration", "Destruction", "Illusion", "Mysticism", "Restoration")
val MAGIC_EFFECTS = listOf(
    "Water Breathing", "Swift Swim", "Water Walking", "Shield", "Fire Shield", "Lightning Shield", "Frost Shield",
    "Burden", "Feather", "Jump", "Levitate", "Slow Fall", "Lock", "Open", "Fire Damage", "Shock Damage",
    "Frost Damage", "Drain Attribute", "Drain Health", "Drain Magicka", "Drain Fatigue", "Drain Skill",
    "Damage Attribute", "Damage Health", "Damage Magicka", "Damage Fatigue", "Damage Skill", "Poison",
    "Weakness to Fire", "Weakness to Frost", "Weakness to Shock", "Weakness to Magicka",
    "Weakness to Common Disease", "Weakness to Blight Disease", "Weakness to Corprus Disease", "Weakness to Poison",
    "Weakness to Normal Weapons", "Disintegrate Weapon", "Disintegrate Armor", "Invisibility", "Chameleon", "Light",
    "Sanctuary", "Night Eye", "Charm", "Paralyze", "Silence", "Blind", "Sound", "Calm Humanoid", "Calm Creature",
    "Frenzy Humanoid", "Frenzy Creature", "Demoralize Humanoid", "Demoralize Creature", "Rally Humanoid",
    "Rally Creature", "Dispel", "Soultrap", "Telekinesis", "Mark", "Recall", "Divine Intervention",
    "Almsivi Intervention", "Detect Animal", "Detect Enchantment", "Detect Key", "Spell Absorption", "Reflect",
    "Cure Common Disease", "Cure Blight Disease", "Cure Corprus Disease", "Cure Poison", "Cure Paralyzation",
    "Restore Attribute", "Restore Health", "Restore Magicka", "Restore Fatigue", "Restore Skill",
    "Fortify Attribute", "Fortify Health", "Fortify Magicka", "Fortify Fatigue", "Fortify Skill",
    "Fortify Maximum Magicka", "Absorb Attribute", "Absorb Health", "Absorb Magicka", "Absorb Fatigue",
    "Absorb Skill", "Resist Fire", "Resist Frost", "Resist Shock", "Resist Magicka", "Resist Common Disease",
    "Resist Blight Disease", "Resist Corprus Disease", "Resist Poison", "Resist Normal Weapons", "Resist Paralysis",
    "Remove Curse", "Turn Undead", "Summon Scamp", "Summon Clannfear", "Summon Daedroth", "Summon Dremora",
    "Summon Ancestral Ghost", "Summon Skeletal Minion", "Summon Bonewalker", "Summon Greater Bonewalker",
    "Summon Bonelord", "Summon Winged Twilight", "Summon Hunger", "Summon Golden Saint", "Summon Flame Atronach",
    "Summon Frost Atronach", "Summon Storm Atronach", "Fortify Attack", "Command Creature", "Command Humanoid",
    "Bound Dagger", "Bound Longsword", "Bound Mace", "Bound Battle Axe", "Bound Spear", "Bound Longbow",
    "Extra Spell", "Bound Cuirass", "Bound Helm", "Bound Boots", "Bound Shield", "Bound Gloves", "Corprus",
    "Vampirism", "Summon Centurion Sphere", "Sun Damage", "Stunted Magicka", "Summon Fabricant",
    "Call Wolf", "Call Bear", "Summon Bonewolf", "Summon Creature 04", "Summon Creature 05",
)

private val ACTOR_REFS = setOf("NPC_", "CREA", "LEVC")
private val ITEM_REFS = setOf("ALCH", "APPA", "ARMO", "BOOK", "CLOT", "INGR", "LEVI", "LIGH", "LOCK", "MISC", "PROB", "REPA", "WEAP")

// ---- the DSL -----------------------------------------------------------------------------------------------------------

private fun f(name: String, type: FieldType) = FieldDef(name, type)
private fun e(name: String, type: FieldType, values: List<String>, base: Int = 0) = FieldDef(name, type, enum = values, enumBase = base)
private fun fl(name: String, type: FieldType, vararg flags: Pair<Long, String>) = FieldDef(name, type, flags = flags.toMap())
private fun ref(name: String, vararg types: String, fixed: Int = 0) =
    FieldDef(name, if (fixed > 0) FieldType.Fixed(fixed) else FieldType.ZString, refs = types.toSet())

private fun sub(tag: String, label: String, vararg fields: FieldDef, repeats: Boolean = false, required: Boolean = false) =
    SubDef(tag, label, listOf(fields.toList()), repeats, required)

private fun text(tag: String, label: String, required: Boolean = false) = sub(tag, label, f(label, FieldType.ZString), required = required)
private fun longText(tag: String, label: String) = sub(tag, label, f(label, FieldType.Text))
private fun refSub(tag: String, label: String, vararg types: String) = sub(tag, label, ref(label, *types))

private val NAME = text("NAME", "ID", required = true)
private val MODL = text("MODL", "Model")
private val FNAM = text("FNAM", "Name")
private val SCRI = refSub("SCRI", "Script", "SCPT")
private val ITEX = text("ITEX", "Icon")
private val DELE = sub("DELE", "Deleted", f("Deleted", FieldType.I32))
private val DESC = longText("DESC", "Description")

/** A spell or enchantment effect (ENAM), 24 bytes. */
private val EFFECT = sub(
    "ENAM", "Effect",
    e("Effect", FieldType.I16, MAGIC_EFFECTS),
    FieldDef("Skill", FieldType.I8, enum = listOf("None") + SKILLS, enumBase = -1),
    FieldDef("Attribute", FieldType.I8, enum = listOf("None") + ATTRIBUTES, enumBase = -1),
    e("Range", FieldType.I32, listOf("Self", "Touch", "Target")),
    f("Area", FieldType.I32), f("Duration", FieldType.I32), f("Magnitude min", FieldType.I32), f("Magnitude max", FieldType.I32),
    repeats = true,
)

private val INVENTORY = sub("NPCO", "Item", f("Count", FieldType.I32), ref("Item", *ITEM_REFS.toTypedArray(), fixed = 32), repeats = true)
private val SPELLS = sub("NPCS", "Spell", ref("Spell", "SPEL", fixed = 32), repeats = true)

private val SERVICES = arrayOf(
    0x1L to "Weapons", 0x2L to "Armor", 0x4L to "Clothing", 0x8L to "Books", 0x10L to "Ingredients", 0x20L to "Picks",
    0x40L to "Probes", 0x80L to "Lights", 0x100L to "Apparatus", 0x200L to "Repair items", 0x400L to "Misc",
    0x800L to "Spells", 0x1000L to "Magic items", 0x2000L to "Potions", 0x4000L to "Training", 0x8000L to "Spellmaking",
    0x10000L to "Enchanting", 0x20000L to "Repair",
)

private val AI = listOf(
    sub(
        "AIDT", "AI", f("Hello", FieldType.U16), f("Fight", FieldType.U8), f("Flee", FieldType.U8), f("Alarm", FieldType.U8),
        f("Unknown", FieldType.Bytes(3)), fl("Services", FieldType.I32, *SERVICES),
    ),
    sub(
        "AI_W", "Wander", f("Distance", FieldType.I16), f("Duration", FieldType.I16), f("Time of day", FieldType.U8),
        *Array(8) { f("Idle ${it + 2}", FieldType.U8) }, f("Repeat", FieldType.U8), repeats = true,
    ),
    sub("AI_T", "Travel", f("X", FieldType.F32), f("Y", FieldType.F32), f("Z", FieldType.F32), f("Repeat", FieldType.U8), f("Padding", FieldType.Bytes(3)), repeats = true),
    sub(
        "AI_F", "Follow", f("X", FieldType.F32), f("Y", FieldType.F32), f("Z", FieldType.F32), f("Duration", FieldType.I16),
        ref("Target", *ACTOR_REFS.toTypedArray(), fixed = 32), f("Repeat", FieldType.U8), f("Padding", FieldType.Bytes(1)), repeats = true,
    ),
    sub(
        "AI_E", "Escort", f("X", FieldType.F32), f("Y", FieldType.F32), f("Z", FieldType.F32), f("Duration", FieldType.I16),
        ref("Target", *ACTOR_REFS.toTypedArray(), fixed = 32), f("Repeat", FieldType.U8), f("Padding", FieldType.Bytes(1)), repeats = true,
    ),
    sub("AI_A", "Activate", f("Target", FieldType.Fixed(32)), f("Repeat", FieldType.U8), repeats = true),
    text("CNDT", "Cell (for follow/escort)"),
    sub(
        "DODT", "Travel destination", f("X", FieldType.F32), f("Y", FieldType.F32), f("Z", FieldType.F32),
        f("Rot X", FieldType.F32), f("Rot Y", FieldType.F32), f("Rot Z", FieldType.F32), repeats = true,
    ),
    text("DNAM", "Destination cell"),
)

private val BODY_PARTS = listOf(
    "Head", "Hair", "Neck", "Cuirass", "Groin", "Skirt", "Right hand", "Left hand", "Right wrist", "Left wrist",
    "Shield", "Right forearm", "Left forearm", "Right upper arm", "Left upper arm", "Right foot", "Left foot",
    "Right ankle", "Left ankle", "Right knee", "Left knee", "Right upper leg", "Left upper leg", "Right pauldron",
    "Left pauldron", "Weapon", "Tail",
)

/** Armor and clothing: which body parts they cover (INDX, then the male and female part). */
private val PARTS = listOf(
    sub("INDX", "Covers", e("Body part", FieldType.U8, BODY_PARTS), repeats = true),
    refSub("BNAM", "Male part", "BODY"),
    refSub("CNAM", "Female part", "BODY"),
)

private fun item(tag: String, label: String, data: SubDef, vararg extra: SubDef) =
    RecordDef(tag, label, listOf(NAME, DELE, MODL, FNAM, data, SCRI, ITEX) + extra)

private fun levelled(tag: String, label: String, entry: String, refs: Set<String>, flags: Array<Pair<Long, String>>) = RecordDef(
    tag, label, listOf(
        NAME, DELE,
        sub("DATA", "Flags", fl("Flags", FieldType.I32, *flags)),
        sub("NNAM", "Chance none", f("Chance none", FieldType.U8)),
        sub("INDX", "Count", f("Count", FieldType.I32)),
        SubDef(entry, "Entry", listOf(listOf(FieldDef("Object", FieldType.ZString, refs = refs))), repeats = true),
        sub("INTV", "Level", f("Level", FieldType.I16), repeats = true),
    ),
)

// ---- the records -------------------------------------------------------------------------------------------------------

val RECORD_DEFS: Map<String, RecordDef> = listOf(
    RecordDef(
        "TES3", "Header", listOf(
            sub(
                "HEDR", "Header", f("Version", FieldType.F32), e("Type", FieldType.U32, listOf("Plugin (esp)", "Master (esm)")),
                f("Author", FieldType.Fixed(32)), f("Description", FieldType.Fixed(256)), f("Records", FieldType.I32), required = true,
            ),
            sub("MAST", "Master", f("Master", FieldType.ZString), repeats = true),
            sub("DATA", "Master size", f("Size", FieldType.I64), repeats = true),
        ),
    ),
    RecordDef(
        "GMST", "Game setting", listOf(
            NAME, DELE, sub("STRV", "Text", f("Text", FieldType.Text)), sub("INTV", "Integer", f("Integer", FieldType.I32)),
            sub("FLTV", "Float", f("Float", FieldType.F32)),
        ),
    ),
    RecordDef(
        "GLOB", "Global", listOf(
            NAME, DELE, sub("FNAM", "Type", FieldDef("Type", FieldType.U8, enum = listOf("short", "long", "float"), enumCodes = listOf('s'.code.toLong(), 'l'.code.toLong(), 'f'.code.toLong()))),
            sub("FLTV", "Value", f("Value", FieldType.F32)),
        ),
    ),
    RecordDef(
        "CLAS", "Class", listOf(
            NAME, DELE, FNAM,
            sub(
                "CLDT", "Class data",
                e("Attribute 1", FieldType.I32, ATTRIBUTES), e("Attribute 2", FieldType.I32, ATTRIBUTES),
                e("Specialization", FieldType.I32, SPECIALIZATIONS),
                *Array(10) { i -> e(if (i % 2 == 0) "Minor skill ${i / 2 + 1}" else "Major skill ${i / 2 + 1}", FieldType.I32, SKILLS) },
                fl("Playable", FieldType.I32, 1L to "Playable"), fl("Services", FieldType.I32, *SERVICES),
            ),
            DESC,
        ),
    ),
    RecordDef(
        "FACT", "Faction", listOf(
            NAME, DELE, FNAM,
            sub("RNAM", "Rank", f("Rank", FieldType.Fixed(32)), repeats = true),
            sub(
                "FADT", "Faction data",
                e("Attribute 1", FieldType.I32, ATTRIBUTES), e("Attribute 2", FieldType.I32, ATTRIBUTES),
                *(1..10).flatMap { r ->
                    listOf(
                        f("Rank $r attribute 1", FieldType.I32), f("Rank $r attribute 2", FieldType.I32),
                        f("Rank $r primary skill", FieldType.I32), f("Rank $r favoured skill", FieldType.I32),
                        f("Rank $r reputation", FieldType.I32),
                    )
                }.toTypedArray(),
                *Array(7) { FieldDef("Skill ${it + 1}", FieldType.I32, enum = listOf("None") + SKILLS, enumBase = -1) },
                fl("Flags", FieldType.I32, 1L to "Hidden from player"),
            ),
            refSub("ANAM", "Reaction faction", "FACT").let { SubDef(it.tag, it.label, it.layouts, repeats = true) },
            sub("INTV", "Reaction", f("Reaction", FieldType.I32), repeats = true),
        ),
    ),
    RecordDef(
        "RACE", "Race", listOf(
            NAME, DELE, FNAM,
            sub(
                "RADT", "Race data",
                *(1..7).flatMap { listOf(FieldDef("Skill $it", FieldType.I32, enum = listOf("None") + SKILLS, enumBase = -1), f("Skill $it bonus", FieldType.I32)) }.toTypedArray(),
                *ATTRIBUTES.flatMap { listOf(f("$it (male)", FieldType.I32), f("$it (female)", FieldType.I32)) }.toTypedArray(),
                f("Height (male)", FieldType.F32), f("Height (female)", FieldType.F32),
                f("Weight (male)", FieldType.F32), f("Weight (female)", FieldType.F32),
                fl("Flags", FieldType.I32, 1L to "Playable", 2L to "Beast race"),
            ),
            SPELLS, DESC,
        ),
    ),
    RecordDef(
        "SKIL", "Skill", listOf(
            sub("INDX", "Skill", e("Skill", FieldType.I32, SKILLS), required = true), DELE,
            sub(
                "SKDT", "Skill data", e("Attribute", FieldType.I32, ATTRIBUTES), e("Specialization", FieldType.I32, SPECIALIZATIONS),
                f("Use value 1", FieldType.F32), f("Use value 2", FieldType.F32), f("Use value 3", FieldType.F32), f("Use value 4", FieldType.F32),
            ),
            DESC,
        ),
    ),
    RecordDef(
        "MGEF", "Magic effect", listOf(
            sub("INDX", "Effect", e("Effect", FieldType.I32, MAGIC_EFFECTS), required = true), DELE,
            sub(
                "MEDT", "Effect data", e("School", FieldType.I32, SCHOOLS), f("Base cost", FieldType.F32),
                fl(
                    "Flags", FieldType.I32, 0x1L to "Target skill", 0x2L to "Target attribute", 0x4L to "No duration",
                    0x8L to "No magnitude", 0x10L to "Harmful", 0x20L to "Continuous VFX", 0x40L to "Cast self",
                    0x80L to "Cast touch", 0x100L to "Cast target", 0x200L to "Spellmaking", 0x400L to "Enchanting",
                    0x800L to "Negative light",
                ),
                f("Red", FieldType.I32), f("Green", FieldType.I32), f("Blue", FieldType.I32),
                f("Size X", FieldType.F32), f("Speed", FieldType.F32), f("Size cap", FieldType.F32),
            ),
            text("ITEX", "Icon"), text("PTEX", "Particle texture"),
            refSub("BSND", "Bolt sound", "SOUN"), refSub("CSND", "Cast sound", "SOUN"),
            refSub("HSND", "Hit sound", "SOUN"), refSub("ASND", "Area sound", "SOUN"),
            refSub("CVFX", "Cast visual", "STAT"), refSub("BVFX", "Bolt visual", "WEAP"),
            refSub("HVFX", "Hit visual", "STAT"), refSub("AVFX", "Area visual", "STAT"),
            DESC,
        ),
    ),
    RecordDef(
        "SCPT", "Script", listOf(
            sub(
                "SCHD", "Script header", f("Name", FieldType.Fixed(32)), f("Shorts", FieldType.I32), f("Longs", FieldType.I32),
                f("Floats", FieldType.I32), f("Compiled size", FieldType.I32), f("Local variables size", FieldType.I32), required = true,
            ),
            DELE,
            SubDef("SCVR", "Variable names", emptyList()),
            SubDef("SCDT", "Compiled code", emptyList()),
            longText("SCTX", "Source"),
        ),
    ),
    RecordDef(
        "REGN", "Region", listOf(
            NAME, DELE, FNAM,
            SubDef(
                "WEAT", "Weather chances", listOf(
                    listOf("Clear", "Cloudy", "Foggy", "Overcast", "Rain", "Thunder", "Ash", "Blight").map { f(it, FieldType.U8) },
                    listOf("Clear", "Cloudy", "Foggy", "Overcast", "Rain", "Thunder", "Ash", "Blight", "Snow", "Blizzard").map { f(it, FieldType.U8) },
                ),
            ),
            refSub("BNAM", "Sleep creature", "LEVC"),
            sub("CNAM", "Map colour", f("Red", FieldType.U8), f("Green", FieldType.U8), f("Blue", FieldType.U8), f("Unused", FieldType.U8)),
            sub("SNAM", "Sound", ref("Sound", "SOUN", fixed = 32), f("Chance", FieldType.U8), repeats = true),
        ),
    ),
    RecordDef(
        "BSGN", "Birthsign", listOf(NAME, DELE, FNAM, text("TNAM", "Texture"), DESC, SPELLS),
    ),
    RecordDef(
        "SNDG", "Sound generator", listOf(
            NAME, DELE,
            sub(
                "DATA", "Type",
                e("Type", FieldType.I32, listOf("Left foot", "Right foot", "Swim left", "Swim right", "Moan", "Roar", "Scream", "Land")),
            ),
            refSub("CNAM", "Creature", "CREA"), refSub("SNAM", "Sound", "SOUN"),
        ),
    ),
    RecordDef(
        "SOUN", "Sound", listOf(
            NAME, DELE, text("FNAM", "Sound file"),
            sub("DATA", "Sound data", f("Volume", FieldType.U8), f("Min range", FieldType.U8), f("Max range", FieldType.U8)),
        ),
    ),
    RecordDef(
        "SPEL", "Spell", listOf(
            NAME, DELE, FNAM,
            sub(
                "SPDT", "Spell data", e("Type", FieldType.I32, listOf("Spell", "Ability", "Blight", "Disease", "Curse", "Power")),
                f("Cost", FieldType.I32), fl("Flags", FieldType.I32, 1L to "Autocalc", 2L to "Player start spell", 4L to "Always succeeds"),
            ),
            EFFECT,
        ),
    ),
    RecordDef("LTEX", "Land texture", listOf(NAME, DELE, sub("INTV", "Index", f("Index", FieldType.I32)), text("DATA", "Texture"))),
    RecordDef("STAT", "Static", listOf(NAME, DELE, MODL)),
    RecordDef(
        "DOOR", "Door", listOf(NAME, DELE, MODL, FNAM, SCRI, refSub("SNAM", "Open sound", "SOUN"), refSub("ANAM", "Close sound", "SOUN")),
    ),
    item("MISC", "Misc item", sub("MCDT", "Item data", f("Weight", FieldType.F32), f("Value", FieldType.I32), fl("Flags", FieldType.I32, 1L to "Key"))),
    item(
        "WEAP", "Weapon",
        sub(
            "WPDT", "Weapon data", f("Weight", FieldType.F32), f("Value", FieldType.I32),
            e(
                "Type", FieldType.I16, listOf(
                    "Short blade", "Long blade (one hand)", "Long blade (two hands)", "Blunt (one hand)",
                    "Blunt (two hands, close)", "Blunt (two hands, wide)", "Spear", "Axe (one hand)", "Axe (two hands)",
                    "Bow", "Crossbow", "Thrown", "Arrow", "Bolt",
                ),
            ),
            f("Health", FieldType.U16), f("Speed", FieldType.F32), f("Reach", FieldType.F32), f("Enchantment points", FieldType.U16),
            f("Chop min", FieldType.U8), f("Chop max", FieldType.U8), f("Slash min", FieldType.U8), f("Slash max", FieldType.U8),
            f("Thrust min", FieldType.U8), f("Thrust max", FieldType.U8),
            fl("Flags", FieldType.I32, 1L to "Ignores normal weapon resistance", 2L to "Silver"),
        ),
        refSub("ENAM", "Enchantment", "ENCH"),
    ),
    RecordDef(
        "CONT", "Container", listOf(
            NAME, DELE, MODL, FNAM, sub("CNDT", "Capacity", f("Capacity", FieldType.F32)),
            sub("FLAG", "Flags", fl("Flags", FieldType.I32, 1L to "Organic", 2L to "Respawns", 8L to "Default")),
            SCRI, INVENTORY,
        ),
    ),
    RecordDef(
        "CREA", "Creature", listOf(
            NAME, DELE, MODL, refSub("CNAM", "Sound generator creature", "CREA"), FNAM, SCRI,
            sub(
                "NPDT", "Creature data",
                e("Type", FieldType.I32, listOf("Creature", "Daedra", "Undead", "Humanoid")), f("Level", FieldType.I32),
                *ATTRIBUTES.map { f(it, FieldType.I32) }.toTypedArray(),
                f("Health", FieldType.I32), f("Magicka", FieldType.I32), f("Fatigue", FieldType.I32), f("Soul", FieldType.I32),
                f("Combat", FieldType.I32), f("Magic", FieldType.I32), f("Stealth", FieldType.I32),
                f("Attack 1 min", FieldType.I32), f("Attack 1 max", FieldType.I32), f("Attack 2 min", FieldType.I32),
                f("Attack 2 max", FieldType.I32), f("Attack 3 min", FieldType.I32), f("Attack 3 max", FieldType.I32),
                f("Gold", FieldType.I32),
            ),
            sub(
                "FLAG", "Flags", fl(
                    "Flags", FieldType.I32, 0x1L to "Bipedal", 0x2L to "Respawn", 0x4L to "Weapon and shield", 0x8L to "Default",
                    0x10L to "Swims", 0x20L to "Flies", 0x40L to "Walks", 0x80L to "Essential", 0x400L to "Skeleton blood",
                    0x800L to "Metal blood",
                ),
            ),
            sub("XSCL", "Scale", f("Scale", FieldType.F32)),
            INVENTORY, SPELLS,
        ) + AI,
    ),
    RecordDef(
        "BODY", "Body part", listOf(
            NAME, DELE, MODL, refSub("FNAM", "Race", "RACE"),
            sub(
                "BYDT", "Body part data",
                e("Part", FieldType.U8, listOf("Head", "Hair", "Neck", "Chest", "Groin", "Hand", "Wrist", "Forearm", "Upper arm", "Foot", "Ankle", "Knee", "Upper leg", "Clavicle", "Tail")),
                fl("Vampire", FieldType.U8, 1L to "Vampire"), fl("Flags", FieldType.U8, 1L to "Female", 2L to "Not playable"),
                e("Type", FieldType.U8, listOf("Skin", "Clothing", "Armor")),
            ),
        ),
    ),
    RecordDef(
        "LIGH", "Light", listOf(
            NAME, DELE, MODL, FNAM, ITEX,
            sub(
                "LHDT", "Light data", f("Weight", FieldType.F32), f("Value", FieldType.I32), f("Time", FieldType.I32),
                f("Radius", FieldType.I32), f("Red", FieldType.U8), f("Green", FieldType.U8), f("Blue", FieldType.U8), f("Alpha", FieldType.U8),
                fl(
                    "Flags", FieldType.I32, 0x1L to "Dynamic", 0x2L to "Can carry", 0x4L to "Negative", 0x8L to "Flicker",
                    0x10L to "Fire", 0x20L to "Off by default", 0x40L to "Flicker slow", 0x80L to "Pulse", 0x100L to "Pulse slow",
                ),
            ),
            refSub("SNAM", "Sound", "SOUN"), SCRI,
        ),
    ),
    RecordDef(
        "ENCH", "Enchantment", listOf(
            NAME, DELE,
            sub(
                "ENDT", "Enchantment data", e("Type", FieldType.I32, listOf("Cast once", "Cast when strikes", "Cast when used", "Constant effect")),
                f("Cost", FieldType.I32), f("Charge", FieldType.I32), fl("Flags", FieldType.I32, 1L to "Autocalc"),
            ),
            EFFECT,
        ),
    ),
    RecordDef(
        "NPC_", "NPC", listOf(
            NAME, DELE, MODL, FNAM, refSub("RNAM", "Race", "RACE"), refSub("CNAM", "Class", "CLAS"),
            refSub("ANAM", "Faction", "FACT"), refSub("BNAM", "Head", "BODY"), refSub("KNAM", "Hair", "BODY"), SCRI,
            SubDef(
                "NPDT", "NPC data", listOf(
                    listOf(f("Level", FieldType.I16)) + ATTRIBUTES.map { f(it, FieldType.U8) } + SKILLS.map { f(it, FieldType.U8) } + listOf(
                        f("Unknown", FieldType.Bytes(1)), f("Health", FieldType.U16), f("Magicka", FieldType.U16), f("Fatigue", FieldType.U16),
                        f("Disposition", FieldType.U8), f("Reputation", FieldType.U8), f("Rank", FieldType.U8), f("Unknown 2", FieldType.Bytes(1)),
                        f("Gold", FieldType.I32),
                    ),
                    listOf(
                        f("Level", FieldType.I16), f("Disposition", FieldType.U8), f("Reputation", FieldType.U8), f("Rank", FieldType.U8),
                        f("Unknown", FieldType.Bytes(3)), f("Gold", FieldType.I32),
                    ),
                ),
            ),
            sub(
                "FLAG", "Flags", fl(
                    "Flags", FieldType.I32, 0x1L to "Female", 0x2L to "Essential", 0x4L to "Respawn", 0x8L to "Default",
                    0x10L to "Autocalc stats", 0x400L to "Skeleton blood", 0x800L to "Metal blood",
                ),
            ),
            INVENTORY, SPELLS,
        ) + AI + listOf(sub("XSCL", "Scale", f("Scale", FieldType.F32))),
    ),
    item(
        "ARMO", "Armor",
        sub(
            "AODT", "Armor data",
            e("Type", FieldType.I32, listOf("Helmet", "Cuirass", "Left pauldron", "Right pauldron", "Greaves", "Boots", "Left gauntlet", "Right gauntlet", "Shield", "Left bracer", "Right bracer")),
            f("Weight", FieldType.F32), f("Value", FieldType.I32), f("Health", FieldType.I32), f("Enchantment points", FieldType.I32), f("Armor rating", FieldType.I32),
        ),
        *PARTS.toTypedArray(), refSub("ENAM", "Enchantment", "ENCH"),
    ),
    item(
        "CLOT", "Clothing",
        sub(
            "CTDT", "Clothing data",
            e("Type", FieldType.I32, listOf("Pants", "Shoes", "Shirt", "Belt", "Robe", "Right glove", "Left glove", "Skirt", "Ring", "Amulet")),
            f("Weight", FieldType.F32), f("Value", FieldType.U16), f("Enchantment points", FieldType.U16),
        ),
        *PARTS.toTypedArray(), refSub("ENAM", "Enchantment", "ENCH"),
    ),
    item("REPA", "Repair item", sub("RIDT", "Repair data", f("Weight", FieldType.F32), f("Value", FieldType.I32), f("Uses", FieldType.I32), f("Quality", FieldType.F32))),
    RecordDef("ACTI", "Activator", listOf(NAME, DELE, MODL, FNAM, SCRI)),
    item(
        "APPA", "Apparatus",
        sub(
            "AADT", "Apparatus data", e("Type", FieldType.I32, listOf("Mortar and pestle", "Alembic", "Calcinator", "Retort")),
            f("Quality", FieldType.F32), f("Weight", FieldType.F32), f("Value", FieldType.I32),
        ),
    ),
    item("LOCK", "Lockpick", sub("LKDT", "Lockpick data", f("Weight", FieldType.F32), f("Value", FieldType.I32), f("Quality", FieldType.F32), f("Uses", FieldType.I32))),
    item("PROB", "Probe", sub("PBDT", "Probe data", f("Weight", FieldType.F32), f("Value", FieldType.I32), f("Quality", FieldType.F32), f("Uses", FieldType.I32))),
    item(
        "INGR", "Ingredient",
        sub(
            "IRDT", "Ingredient data", f("Weight", FieldType.F32), f("Value", FieldType.I32),
            *Array(4) { FieldDef("Effect ${it + 1}", FieldType.I32, enum = listOf("None") + MAGIC_EFFECTS, enumBase = -1) },
            *Array(4) { FieldDef("Effect ${it + 1} skill", FieldType.I32, enum = listOf("None") + SKILLS, enumBase = -1) },
            *Array(4) { FieldDef("Effect ${it + 1} attribute", FieldType.I32, enum = listOf("None") + ATTRIBUTES, enumBase = -1) },
        ),
    ),
    item(
        "BOOK", "Book",
        sub(
            "BKDT", "Book data", f("Weight", FieldType.F32), f("Value", FieldType.I32), fl("Scroll", FieldType.I32, 1L to "Scroll"),
            FieldDef("Teaches", FieldType.I32, enum = listOf("Nothing") + SKILLS, enumBase = -1), f("Enchantment points", FieldType.I32),
        ),
        longText("TEXT", "Text"), refSub("ENAM", "Enchantment", "ENCH"),
    ),
    RecordDef(
        "ALCH", "Potion", listOf(
            NAME, DELE, MODL, text("TEXT", "Icon"), SCRI, FNAM,
            sub("ALDT", "Potion data", f("Weight", FieldType.F32), f("Value", FieldType.I32), fl("Flags", FieldType.I32, 1L to "Autocalc")),
            EFFECT,
        ),
    ),
    RecordDef(
        "CELL", "Cell", listOf(
            text("NAME", "Name"),
            SubDef(
                "DATA", "Cell data / position",
                listOf(
                    listOf(
                        fl("Flags", FieldType.U32, 0x1L to "Interior", 0x2L to "Has water", 0x4L to "Illegal to sleep", 0x80L to "Behaves like exterior"),
                        f("Grid X", FieldType.I32), f("Grid Y", FieldType.I32),
                    ),
                    listOf(
                        f("X", FieldType.F32), f("Y", FieldType.F32), f("Z", FieldType.F32),
                        f("Rotation X", FieldType.F32), f("Rotation Y", FieldType.F32), f("Rotation Z", FieldType.F32),
                    ),
                ),
                required = true,
            ),
            DELE,
            sub("INTV", "Water height / uses", f("Value", FieldType.I32), repeats = true),
            sub("WHGT", "Water height", f("Water height", FieldType.F32)),
            sub(
                "AMBI", "Lighting",
                *listOf("Ambient", "Sunlight", "Fog").flatMap { c -> listOf("R", "G", "B", "A").map { f("$c $it", FieldType.U8) } }.toTypedArray(),
                f("Fog density", FieldType.F32),
            ),
            refSub("RGNN", "Region", "REGN"),
            sub("NAM5", "Map colour", f("R", FieldType.U8), f("G", FieldType.U8), f("B", FieldType.U8), f("A", FieldType.U8)),
            sub("NAM0", "Temporary references", f("Count", FieldType.I32), repeats = true),
            // references
            sub("MVRF", "Moved reference", f("Reference number", FieldType.U32), repeats = true),
            sub("CNDT", "Moved to cell", f("Grid X", FieldType.I32), f("Grid Y", FieldType.I32), repeats = true),
            sub("FRMR", "Reference number", f("Reference number", FieldType.U32), repeats = true),
            sub("UNAM", "Blocked", f("Blocked", FieldType.U8), repeats = true),
            sub("XSCL", "Scale", f("Scale", FieldType.F32), repeats = true),
            sub("ANAM", "Owner", ref("Owner", "NPC_"), repeats = true),
            sub("BNAM", "Global variable", ref("Global", "GLOB"), repeats = true),
            sub("XSOL", "Soul", ref("Soul", "CREA"), repeats = true),
            sub("CNAM", "Faction", ref("Faction", "FACT"), repeats = true),
            sub("INDX", "Faction rank", f("Rank", FieldType.I32), repeats = true),
            sub("XCHG", "Enchantment charge", f("Charge", FieldType.F32), repeats = true),
            sub("NAM9", "Count", f("Count", FieldType.I32), repeats = true),
            sub(
                "DODT", "Door destination", f("X", FieldType.F32), f("Y", FieldType.F32), f("Z", FieldType.F32),
                f("Rotation X", FieldType.F32), f("Rotation Y", FieldType.F32), f("Rotation Z", FieldType.F32), repeats = true,
            ),
            sub("DNAM", "Door destination cell", f("Cell", FieldType.ZString), repeats = true),
            sub("FLTV", "Lock level", f("Lock level", FieldType.I32), repeats = true),
            sub("KNAM", "Key", ref("Key", "MISC"), repeats = true),
            sub("TNAM", "Trap", ref("Trap", "SPEL"), repeats = true),
        ),
    ),
    levelled("LEVI", "Levelled item", "INAM", ITEM_REFS, arrayOf(1L to "Calculate for each item", 2L to "Calculate from all levels")),
    levelled("LEVC", "Levelled creature", "CNAM", ACTOR_REFS, arrayOf(1L to "Calculate from all levels")),
).associateBy { it.tag }

/** The types the editor lists, in the order shown. */
val EDITABLE_TYPES: List<String> = listOf(
    "CELL", "NPC_", "CREA", "LEVC", "WEAP", "ARMO", "CLOT", "MISC", "BOOK", "ALCH", "INGR", "APPA", "LOCK", "PROB", "REPA",
    "LIGH", "LEVI", "CONT", "DOOR", "ACTI", "STAT", "SPEL", "ENCH", "MGEF", "CLAS", "RACE", "FACT", "BSGN", "SKIL",
    "GLOB", "GMST", "SCPT", "REGN", "SOUN", "SNDG", "BODY", "LTEX",
)
