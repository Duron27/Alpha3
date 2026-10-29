package org.alpha3.launcher.utils

import org.alpha3.launcher.ui.overlay.MemoryInfo

/**
 * Device level queries and effects (vibration, memory/cpu stats, battery, logcat, ...).
 * Defaults are harmless no-ops so a platform only overrides what it can provide.
 */
open class SystemServices {
    open fun vibrate(amplitude: Int, duration: Long) {}

    /** CPU share of the given process (own process when null), in percent of all cores, since the previous call. */
    open fun cpuProcessUsage(pid: Int? = null): Int = 0

    /** Percent of one core used by the busiest thread of this process in the last [cpuProcessUsage] interval. */
    open fun cpuBusiestThread(): Int = 0
    open fun navmeshProcessId(): Int? = null

    /** The local time of day as HH:mm:ss. */
    open fun clockTime(): String = ""

    /** Storage read and write rate of this process in MiB/s since the previous call. */
    open fun ioRates(): Pair<Float, Float> = 0f to 0f

    open fun availableStorageBytes(): Long = 0L
    open fun memoryInfo(): MemoryInfo = MemoryInfo("", "", "")
    open fun detailedSystemInfo(
        mem: MemoryInfo, cpu: Int, gpuModel: String, gpuUsage: String, gpuTemp: String, gpuClock: String
    ): String = ""

    open fun batteryStatus(): String = ""
    open fun hasInternetPermission(): Boolean = true

    /** Real display size in pixels (width, height). */
    open fun deviceRealSize(): Pair<Int, Int> = 0 to 0

    open fun logcatMessages(level: String): List<String> = emptyList()
    open fun enableLogcat(level: String) {}

    /** Runs a command and returns its output; used to read root-only sysfs nodes. */
    open fun readProcessOutput(vararg command: String): String = ""
}

object SystemInfo {
    var services: SystemServices = SystemServices()
}
