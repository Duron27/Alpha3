package org.alpha3.launcher.utils

/** The GPU family a driver is for: Turnip and Qualcomm's drivers run on Adreno, Mesa PanVK on Mali. */
enum class GpuFamily(val label: String) {
    ADRENO("Adreno"),
    MALI("Mali"),
    UNKNOWN("unknown");

    companion object {
        /** From the driver library's name; [UNKNOWN] when it does not say. */
        fun ofLibrary(libraryName: String): GpuFamily {
            val name = libraryName.lowercase()
            return when {
                "panfrost" in name || "panvk" in name || "mali" in name -> MALI
                "freedreno" in name || "adreno" in name || "turnip" in name -> ADRENO
                else -> UNKNOWN
            }
        }
    }
}

/** A driver that can be downloaded. The zip holds a `meta.json` and the driver library. */
data class GpuDriverPackage(val name: String, val description: String, val url: String, val family: GpuFamily)

/** A driver that has been installed on the device. */
data class InstalledGpuDriver(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val libraryName: String,
    /** Lowest Android API level it runs on. */
    val minApi: Int,
    /** Where it was downloaded from, to show which catalog entries are already installed. */
    val sourceUrl: String = "",
) {
    val family: GpuFamily get() = GpuFamily.ofLibrary(libraryName)
}

/**
 * Downloads, installs and removes custom Vulkan drivers (Android, through libadrenotools).
 *
 * Installed drivers are kept next to the app's settings, so they survive an uninstall like the settings do.
 */
interface GpuDriverManager {
    /** Android API level of this device. */
    val deviceApi: Int

    /** The GPU of this device, which decides the drivers that can run on it. */
    val gpuFamily: GpuFamily

    suspend fun installed(): List<InstalledGpuDriver>

    /**
     * Downloads the zip at [url] and installs it. [onProgress] gets 0..1, or a negative value when the size is
     * not known. Throws with a readable message if the download or the package is bad.
     */
    suspend fun install(url: String, onProgress: (Float) -> Unit): InstalledGpuDriver

    suspend fun remove(id: String)
}

object GpuDrivers {
    /** The Turnip driver built into the app; on Mali, where it cannot run, the device's own driver. */
    const val BUILTIN_ID = "builtin"

    val catalog = listOf(
        GpuDriverPackage(
            "Turnip Gen8 V37",
            "For Adreno 8xx. By StevenMXZ.",
            "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v37/Turnip_Gen8_V37.zip",
            GpuFamily.ADRENO,
        ),
        GpuDriverPackage(
            "Turnip Gen8 V37 (patched)",
            "For Adreno 8xx, patched build. By StevenMXZ.",
            "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v37/Turnip_Gen8_V37_patch.zip",
            GpuFamily.ADRENO,
        ),
        GpuDriverPackage(
            "Turnip v26.3.0-R6 One UI",
            "For Adreno 6xx and 7xx, Samsung One UI. By StevenMXZ.",
            "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v37/Turnip_v26.3.0-R6-Oneui.zip",
            GpuFamily.ADRENO,
        ),
        GpuDriverPackage(
            "Turnip T30 (Mr_Purple_666)",
            "Open-source Vulkan driver, needs Android 11 or newer.",
            "https://github.com/MrPurple666/purple-turnip/releases/download/vturnip_mrpurple_T30-toasted.adpkg/turnip_mrpurple_T30-toasted.adpkg.zip",
            GpuFamily.ADRENO,
        ),
        GpuDriverPackage(
            "Qualcomm v891.7 (Adreno 8xx)",
            "Qualcomm driver from Honor firmware, for Adreno 8xx on Android 16. By StevenMXZ.",
            "https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/download/v891.7/v891.7.zip",
            GpuFamily.ADRENO,
        ),
        GpuDriverPackage(
            "PanVK v1.1.1 Mali-G610 (GameForce Ace)",
            "Mesa's open-source Mali driver on the phone's own kernel driver. Experimental, built for the GameForce Ace. By faux123.",
            "https://github.com/faux123/panvk-faux123-driver-ace/releases/download/v1.1.1/panvk_faux123_v1.1.1.adpkg.zip",
            GpuFamily.MALI,
        ),
        GpuDriverPackage(
            "PanVK v1.1.1 Mali-G77 (Retroid Pocket 4 Pro)",
            "Mesa's open-source Mali driver on the phone's own kernel driver. Experimental, built for the Retroid Pocket 4 Pro. By faux123.",
            "https://github.com/faux123/panvk-faux123-driver-rp4pro/releases/download/v1.1.1/panvk_faux123_v1.1.1.adpkg.zip",
            GpuFamily.MALI,
        ),
        GpuDriverPackage(
            "PanVK v1.1.1 Mali-G57 (Anbernic RG556)",
            "Mesa's open-source Mali driver on the phone's own kernel driver. Experimental, built for the Anbernic RG556. By faux123.",
            "https://github.com/faux123/panvk-faux123-driver-rg556/releases/download/v1.1.1/panvk_faux123_v1.1.1.adpkg.zip",
            GpuFamily.MALI,
        ),
        GpuDriverPackage(
            "PanVK v1.1.1 Mali-G710 (Google Pixel 7)",
            "Mesa's open-source Mali driver on the phone's own kernel driver. Experimental, built for the Google Pixel 7. By faux123.",
            "https://github.com/faux123/panvk-faux123-driver-pixel7/releases/download/v1.1.1/panvk_faux123_v1.1.1.adpkg.zip",
            GpuFamily.MALI,
        ),
    )
}
