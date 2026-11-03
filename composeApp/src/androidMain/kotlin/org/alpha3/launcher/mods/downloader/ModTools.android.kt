package org.alpha3.launcher.mods.downloader

import org.alpha3.launcher.utils.Log

internal actual object ModTools {
    actual val available: Boolean by lazy {
        try {
            System.loadLibrary("alpha3_modtools")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("ModTools", "The mod tools are not in this build: ${e.message}")
            false
        }
    }

    actual fun lootSort(requestJson: String): String = ModToolsNative.lootSort(requestJson)

    actual fun wabbajackPlan(requestJson: String): String = ModToolsNative.wabbajackPlan(requestJson)
}

/** cpp/modtools's JNI entry points (Java_org_alpha3_launcher_mods_downloader_ModToolsNative_*). */
object ModToolsNative {
    @JvmStatic
    external fun lootSort(requestJson: String): String

    @JvmStatic
    external fun wabbajackPlan(requestJson: String): String
}
