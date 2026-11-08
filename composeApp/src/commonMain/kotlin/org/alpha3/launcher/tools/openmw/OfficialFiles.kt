package org.alpha3.launcher.tools.openmw

/**
 * The content files Bethesda shipped: the three masters and the official plugins. They are read-only for every
 * tool in Alpha3; a change to them is always written as a new mod that has them as masters.
 */
internal val OFFICIAL_CONTENT: Set<String> = setOf(
    "morrowind.esm", "tribunal.esm", "bloodmoon.esm",
    "adamantiumarmor.esp", "areaeffectarrows.esp", "bcsounds.esp", "ebq_artifact.esp", "entertainers.esp",
    "lefemmarmor.esp", "master_index.esp", "siege at firemoth.esp",
)

/** True for an official Morrowind content file, by name or path. */
internal fun isOfficialContent(nameOrPath: String): Boolean =
    nameOrPath.replace('\\', '/').substringAfterLast('/').lowercase() in OFFICIAL_CONTENT

/** Fails when [path] is an official content file: nothing may write to one. */
internal fun refuseOfficial(path: String) {
    if (isOfficialContent(path)) fail("${baseName(path)} is an official Morrowind file and is read-only; save the change as a new mod instead")
}
