package org.alpha3.launcher.mods.downloader

// cpp/modtools builds for iOS as a static library with C entry points (include/alpha3_modtools.h:
// alpha3_loot_sort, alpha3_wabbajack_plan, alpha3_modtools_free; cpp/modtools/build-ios.sh). Until it is linked in
// through a cinterop, the organizer sorts by masters and Wabbajack lists cannot install.
internal actual object ModTools {
    actual val available: Boolean = false

    actual fun lootSort(requestJson: String): String = """{"error":"The mod tools are not in the iOS build yet"}"""

    actual fun wabbajackPlan(requestJson: String): String = """{"error":"The mod tools are not in the iOS build yet"}"""
}
