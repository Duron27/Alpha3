@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import org.alpha3.launcher.io.File
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.resources.battery
import org.alpha3.launcher.resources.charging
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.overlay.MemoryInfo
import org.alpha3.launcher.ui.view.humanReadableByteCountBin
import org.alpha3.launcher.utils.SystemServices
import org.alpha3.launcher.utils.stringRes
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.thermalState
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIScreen
import platform.darwin.TH_USAGE_SCALE
import platform.darwin.THREAD_BASIC_INFO
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_threads
import platform.darwin.thread_act_array_tVar
import platform.darwin.thread_basic_info
import platform.darwin.thread_info
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_port_deallocate
import platform.darwin.vm_deallocate
import platform.darwin.task_vm_info
import platform.darwin.TASK_VM_INFO
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage
import platform.posix.uname
import platform.posix.utsname
import kotlin.time.TimeSource

/**
 * iOS implementation of [SystemServices]: what the in-game overlay's switches read (performance hub, memory and CPU info,
 * battery, the engine's log). iOS exposes no GPU counters or temperatures to apps, so those stay blank; the thermal
 * state, which is what throttling follows, is part of the system info.
 */
class IosSystemServices : SystemServices() {
    private val cores = NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceAtLeast(1)

    private var lastBusiestThread = 0

    /** The own process's CPU share in percent of all cores, from the threads' recent usage (the kernel keeps the average). */
    override fun cpuProcessUsage(pid: Int?): Int = memScoped {
        if (pid != null) return@memScoped 0
        val threads = alloc<thread_act_array_tVar>()
        val count = alloc<mach_msg_type_number_tVar>()
        if (task_threads(mach_task_self_, threads.ptr, count.ptr) != 0) return@memScoped 0
        val list = threads.value ?: return@memScoped 0
        var total = 0
        var busiest = 0
        val info = alloc<thread_basic_info>()
        val infoCount = alloc<mach_msg_type_number_tVar>()
        for (i in 0 until count.value.toInt()) {
            infoCount.value = (sizeOf<thread_basic_info>() / sizeOf<UIntVar>()).convert()
            if (thread_info(list[i], THREAD_BASIC_INFO.convert(), info.ptr.reinterpret(), infoCount.ptr) == 0 &&
                (info.flags and 2) == 0 // TH_FLAGS_IDLE
            ) {
                total += info.cpu_usage
                busiest = maxOf(busiest, info.cpu_usage)
            }
            mach_port_deallocate(mach_task_self_, list[i])
        }
        vm_deallocate(mach_task_self_, threads.value!!.rawValue.toLong().convert(), (count.value.toLong() * 4).convert())
        // cpu_usage is 0..TH_USAGE_SCALE for one core
        lastBusiestThread = (busiest * 100 / TH_USAGE_SCALE).coerceIn(0, 100)
        (total * 100 / TH_USAGE_SCALE / cores).coerceIn(0, 100)
    }

    override fun cpuBusiestThread(): Int = lastBusiestThread

    override fun clockTime(): String = NSDateFormatter().apply { dateFormat = "HH:mm:ss" }.stringFromDate(NSDate())

    private var lastIo: Triple<Long, Long, TimeSource.Monotonic.ValueTimeMark>? = null

    /** getrusage counts block operations (the disk cache hides many reads), so this is a rough read and write rate. */
    override fun ioRates(): Pair<Float, Float> = memScoped {
        val usage = alloc<rusage>()
        if (getrusage(RUSAGE_SELF, usage.ptr) != 0) return@memScoped 0f to 0f
        val read = usage.ru_inblock * BLOCK_BYTES
        val write = usage.ru_oublock * BLOCK_BYTES
        val now = TimeSource.Monotonic.markNow()
        val previous = lastIo
        lastIo = Triple(read, write, now)
        if (previous == null) return@memScoped 0f to 0f
        val seconds = (now - previous.third).inWholeMilliseconds / 1000f
        if (seconds <= 0f) return@memScoped 0f to 0f
        val mib = 1024f * 1024f
        ((read - previous.first) / mib / seconds).coerceAtLeast(0f) to ((write - previous.second) / mib / seconds).coerceAtLeast(0f)
    }

    override fun availableStorageBytes(): Long =
        (NSFileManager.defaultManager.attributesOfFileSystemForPath(NSHomeDirectory(), null)?.get(NSFileSystemFreeSize) as? NSNumber)
            ?.longLongValue ?: 0L

    /** The app's own footprint, which is what iOS ends an app for (the same number Xcode's memory gauge shows). */
    private fun footprintBytes(): Long = memScoped {
        val info = alloc<task_vm_info>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info>() / sizeOf<UIntVar>()).convert()
        if (task_info(mach_task_self_, TASK_VM_INFO.convert(), info.ptr.reinterpret(), count.ptr) == 0) info.phys_footprint.toLong() else 0L
    }

    /** What the app may still allocate before iOS ends it (its limit depends on the device). */
    private fun headroomBytes(): Long {
        val fn = availableMemory ?: platform.posix.dlsym(platform.posix.dlopen(null, platform.posix.RTLD_NOW), "os_proc_available_memory")
            ?.reinterpret<kotlinx.cinterop.CFunction<() -> ULong>>()?.also { availableMemory = it } ?: return 0L
        return fn.invoke().toLong()
    }

    private var availableMemory: kotlinx.cinterop.CPointer<kotlinx.cinterop.CFunction<() -> ULong>>? = null

    /**
     * Memory is shown for the app, not the phone: "used" is the app's footprint and "available" what is left of the
     * limit iOS gives it, since the phone's free memory says nothing about when the app gets ended.
     */
    override fun memoryInfo(): MemoryInfo {
        val footprint = footprintBytes()
        val limit = footprint + headroomBytes()
        return MemoryInfo(
            humanReadableByteCountBin(limit), humanReadableByteCountBin(limit - footprint), humanReadableByteCountBin(footprint),
            limit, limit - footprint
        )
    }

    private fun model(): String {
        NSProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"]?.let { return "$it (simulator)" }
        return memScoped {
            val name = alloc<utsname>()
            uname(name.ptr)
            name.machine.toKString()
        }
    }

    private fun thermalState(): String = when (NSProcessInfo.processInfo.thermalState) {
        NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> "Nominal"
        NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> "Fair"
        NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> "Serious (throttling)"
        NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> "Critical (throttling hard)"
        else -> "Unknown"
    }

    override fun detailedSystemInfo(
        mem: MemoryInfo, cpu: Int, gpuModel: String, gpuUsage: String, gpuTemp: String, gpuClock: String
    ): String {
        val process = NSProcessInfo.processInfo
        val gpu = MTLCreateSystemDefaultDevice()?.name ?: gpuModel
        return """
        --- SYSTEM RESOURCES ---
        CPU Usage: $cpu% (${cores} cores)
        GPU: $gpu
        Thermal state: ${thermalState()}
        Low Power Mode: ${process.lowPowerModeEnabled}

        --- APP MEMORY ---
        Footprint: ${mem.usedMemory}
        Left before iOS ends the app: ${mem.availableMemory}
        Limit: ${mem.totalMemory}
        Device memory: ${humanReadableByteCountBin(process.physicalMemory.toLong())}

        --- DEVICE INFO ---
        Device: ${model()}
        OS: iOS ${process.operatingSystemVersionString}
        Free storage: ${humanReadableByteCountBin(availableStorageBytes())}
    """.trimIndent()
    }

    override fun batteryStatus(): String {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        if (level < 0f) return "${stringRes(Res.string.battery)}: --" // the simulator has none
        val charging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
        return "${stringRes(Res.string.battery)}: ${(level * 100).toInt()}%${if (charging) " (${stringRes(Res.string.charging)})" else ""}"
    }

    override fun deviceRealSize(): Pair<Int, Int> =
        UIScreen.mainScreen.nativeBounds.useContents { size.width.toInt() to size.height.toInt() }

    // ---- the engine's log, in place of logcat ------------------------------------------------------------------------

    private val levelOrder = "VDIWE"
    private val lineLevel = Regex("""^\S*\[\d\d:\d\d:\d\d\.\d{3} ([A-Z*])]""")

    /** The last [bytes] of the engine's log (openmw.log of the active profile) as lines at [level] or above. */
    private fun engineLog(level: String, bytes: Long, maxLines: Int): List<String> {
        val path = OpenMWPaths.OPENMW_LOG.toPath()
        val fs = FileSystem.SYSTEM
        val size = fs.metadataOrNull(path)?.size ?: return emptyList()
        val start = (size - bytes).coerceAtLeast(0L)
        val text = try {
            fs.openReadOnly(path).use { it.source(start).buffer().readUtf8() }
        } catch (_: Exception) {
            return emptyList()
        }
        val minimum = levelOrder.indexOf(level.firstOrNull() ?: 'V').coerceAtLeast(0)
        var keep = true
        val lines = ArrayList<String>()
        // a read that starts in the middle of the file begins with half a line
        for (line in text.lineSequence().drop(if (start > 0) 1 else 0)) {
            lineLevel.find(line)?.let { match ->
                val code = match.groupValues[1]
                // "*" lines (the engine's config and settings messages) count as information
                keep = levelOrder.indexOf(if (code == "*") 'I' else code[0]) >= minimum
            }
            if (keep) lines += line
        }
        return lines.takeLast(maxLines)
    }

    override fun logcatMessages(level: String): List<String> = engineLog(level, 96 * 1024L, 100)

    /** Writes the engine's recent log to openmw_logcat.txt, as Android saves its logcat there. */
    override fun enableLogcat(level: String) {
        val file = File(OpenMWPaths.ACTIVE_CONFIG + "/openmw_logcat.txt")
        if (file.exists()) file.delete()
        file.writeText(engineLog(level, 1024 * 1024L, 5000).joinToString("\n"))
    }

    private companion object {
        const val BLOCK_BYTES = 4096L
    }
}

/** The engine's recent frame intervals (support/src/ios.cpp), for the performance hub's frame rate and graph. */
object IosFrameTimes {
    private val self = platform.posix.dlopen(null, platform.posix.RTLD_NOW)
    private var read: kotlinx.cinterop.CPointer<kotlinx.cinterop.CFunction<(kotlinx.cinterop.CPointer<kotlinx.cinterop.FloatVar>?, Int) -> Int>>? = null

    fun read(): List<Float> {
        val fn = read ?: platform.posix.dlsym(self, "alpha3_frame_times")?.reinterpret<kotlinx.cinterop.CFunction<(kotlinx.cinterop.CPointer<kotlinx.cinterop.FloatVar>?, Int) -> Int>>()
            ?.also { read = it } ?: return emptyList()
        return memScoped {
            val buffer = allocArray<kotlinx.cinterop.FloatVar>(240)
            val count = fn.invoke(buffer, 240)
            List(count) { buffer[it] }
        }
    }
}
