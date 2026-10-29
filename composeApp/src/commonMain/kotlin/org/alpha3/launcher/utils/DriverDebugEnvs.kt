package org.alpha3.launcher.utils

/** One toggleable driver environment variable shown in the dev settings. */
data class DriverDebugEnv(val key: String, val value: String, val title: String, val subtitle: String) {
    val id get() = "$key=$value"
}

/**
 * Env vars to try when the Freedreno (Turnip) driver renders garbage on some device.
 * Selected ids are saved comma-separated under [GameFilesPreferences.DRIVER_DEBUG_ENVS] and applied on the next launch.
 */
object DriverDebugEnvs {
    val all = listOf(
        DriverDebugEnv("TU_DEBUG", "noubwc", "Disable UBWC", "Turns off Qualcomm's compressed framebuffer format. First thing to try for striped or noisy output."),
        DriverDebugEnv("MESA_VK_WSI_DEBUG", "linear", "Linear swapchain images", "Forces presentable images to be linear, which avoids tiling mismatches with the compositor."),
        DriverDebugEnv("TU_DEBUG", "sysmem", "Sysmem rendering", "Renders straight to memory instead of tiled GMEM."),
        DriverDebugEnv("TU_DEBUG", "forcebin", "Force binning", "Always uses tiled (binning) rendering."),
        DriverDebugEnv("TU_DEBUG", "nolrz", "Disable LRZ", "Turns off low resolution Z, which helps with depth glitches."),
        DriverDebugEnv("TU_DEBUG", "nolrzfc", "Disable LRZ fast clear", "Turns off only the LRZ fast clear."),
        DriverDebugEnv("TU_DEBUG", "noconform", "Skip conformance limits", "Lets the driver expose features it would otherwise hide."),
        DriverDebugEnv("TU_DEBUG", "flushall", "Flush after every draw", "Slow, but rules out missing synchronization."),
        DriverDebugEnv("TU_DEBUG", "syncdraw", "Wait after every draw", "Slow, but rules out missing synchronization."),
        DriverDebugEnv("MESA_VK_WSI_PRESENT_MODE", "fifo", "Present mode: FIFO", "Forces vsync presentation."),
        DriverDebugEnv("MESA_VK_WSI_PRESENT_MODE", "immediate", "Present mode: immediate", "Forces presentation without vsync."),
    )

    /** Builds the env map; several toggles for the same variable (like TU_DEBUG) are merged comma-separated. */
    fun toEnvMap(selectedIds: Set<String>): Map<String, String> =
        all.filter { it.id in selectedIds }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.joinToString(",") }
}
