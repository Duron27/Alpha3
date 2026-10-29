package org.openmw

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Point
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.system.Os
import android.system.OsConstants
import android.view.WindowManager
import androidx.core.content.ContextCompat
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.resources.Res
import org.alpha3.launcher.resources.battery
import org.alpha3.launcher.resources.charging
import org.alpha3.launcher.ui.overlay.MemoryInfo
import org.alpha3.launcher.ui.view.humanReadableByteCountBin
import org.alpha3.launcher.utils.SystemServices
import org.alpha3.launcher.utils.stringRes
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** Android implementation of the shared [SystemServices]. */
class AndroidSystemServices(private val context: Context) : SystemServices() {

    override fun vibrate(amplitude: Int, duration: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (vibrator.hasVibrator()) {
            val strength = if (amplitude < 0) VibrationEffect.DEFAULT_AMPLITUDE else amplitude
            vibrator.vibrate(VibrationEffect.createOneShot(duration, strength))
        }
    }

    override fun navmeshProcessId(): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.find { it.processName.endsWith(":navmesh") }?.pid
    }

    // CPU time (clock ticks) from the last sample, by process or thread, and when each process was sampled
    private val lastTicks = HashMap<String, Long>()
    private val lastSampleNanos = HashMap<String, Long>()
    private var lastBusiestThread = 0
    private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

    /** utime + stime of a /proc/<...>/stat file; the name in the second field may hold spaces, so parse after its ")". */
    private fun cpuTicks(statPath: String): Long? = runCatching {
        val stat = File(statPath).readText()
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        fields[11].toLong() + fields[12].toLong()
    }.getOrNull()

    override fun cpuProcessUsage(pid: Int?): Int {
        val now = System.nanoTime()
        val own = pid == null || pid == Process.myPid()
        val dir = if (own) "/proc/self" else "/proc/$pid"
        val ticks = cpuTicks("$dir/stat") ?: return 0
        val previous = lastTicks.put(dir, ticks)
        val previousNanos = lastSampleNanos.put(dir, now)
        val elapsedNanos = if (previousNanos != null) now - previousNanos else 0L
        val sampled = previous != null && elapsedNanos > 0
        if (own) {
            // The own process also gets its threads sampled, to tell a busy core from a busy phone
            var busiest = 0L
            File("/proc/self/task").list()?.forEach { tid ->
                val t = cpuTicks("/proc/self/task/$tid/stat") ?: return@forEach
                val before = lastTicks.put("t$tid", t)
                if (before != null) busiest = maxOf(busiest, t - before)
            }
            lastBusiestThread = if (sampled) {
                (busiest * 100_000_000_000L / clockTicksPerSecond / elapsedNanos).toInt().coerceIn(0, 100)
            } else 0
        }
        if (!sampled) return 0
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return ((ticks - previous!!) / clockTicksPerSecond.toDouble() / (elapsedNanos / 1e9) * 100.0 / cores)
            .toInt().coerceIn(0, 100)
    }

    override fun cpuBusiestThread(): Int = lastBusiestThread

    override fun clockTime(): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private var lastIo: Triple<Long, Long, Long>? = null // read bytes, write bytes, nanos

    override fun ioRates(): Pair<Float, Float> {
        val values = runCatching {
            File("/proc/self/io").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim().toLong() }
        }.getOrNull() ?: return 0f to 0f
        val now = System.nanoTime()
        val read = values["read_bytes"] ?: return 0f to 0f
        val write = values["write_bytes"] ?: return 0f to 0f
        val previous = lastIo
        lastIo = Triple(read, write, now)
        if (previous == null || now <= previous.third) return 0f to 0f
        val seconds = (now - previous.third) / 1e9f
        val mib = 1024f * 1024f
        return ((read - previous.first) / mib / seconds).coerceAtLeast(0f) to ((write - previous.second) / mib / seconds).coerceAtLeast(0f)
    }

    override fun availableStorageBytes(): Long =
        StatFs(Environment.getExternalStorageDirectory().toString()).availableBytes

    override fun memoryInfo(): MemoryInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)
        val totalMemory = humanReadableByteCountBin(memoryInfo.totalMem)
        val availableMemory = humanReadableByteCountBin(memoryInfo.availMem)
        val usedMemory = humanReadableByteCountBin(memoryInfo.totalMem - memoryInfo.availMem)
        return MemoryInfo(totalMemory, availableMemory, usedMemory, memoryInfo.totalMem, memoryInfo.availMem)
    }

    override fun detailedSystemInfo(
        mem: MemoryInfo, cpu: Int, gpuModel: String, gpuUsage: String, gpuTemp: String, gpuClock: String
    ): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runtime = Runtime.getRuntime()
        val pid = Process.myPid()
        val pMemInfo = am.getProcessMemoryInfo(intArrayOf(pid))[0]

        return """
        --- SYSTEM RESOURCES ---
        CPU Usage: $cpu%
        GPU: $gpuModel ($gpuClock)
        GPU Load: $gpuUsage | Temp: $gpuTemp
        
        --- DEVICE MEMORY (ActivityManager) ---
        Total: ${mem.totalMemory}
        Available: ${mem.availableMemory}
        Used: ${mem.usedMemory}
        Low Memory State: ${am.isLowRamDevice} (Threshold: ${humanReadableByteCountBin(ActivityManager.MemoryInfo().apply { am.getMemoryInfo(this) }.threshold)})
        
        --- PROCESS MEMORY (PSS) ---
        Total PSS: ${pMemInfo.totalPss / 1024} MB
        Dalvik PSS: ${pMemInfo.dalvikPss / 1024} MB
        Native PSS: ${pMemInfo.nativePss / 1024} MB
        Other PSS: ${pMemInfo.otherPss / 1024} MB
        Private Dirty: ${pMemInfo.totalPrivateDirty / 1024} MB
        
        --- JVM RUNTIME ---
        Max Heap: ${humanReadableByteCountBin(runtime.maxMemory())}
        Total Allocated: ${humanReadableByteCountBin(runtime.totalMemory())}
        Free in Heap: ${humanReadableByteCountBin(runtime.freeMemory())}
        
        --- DEVICE INFO ---
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
    """.trimIndent()
    }

    override fun batteryStatus(): String {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0
        val batteryPct = (level / scale.toFloat()) * 100
        return "${stringRes(Res.string.battery)}: ${batteryPct.toInt()}%${if (isCharging) " (${stringRes(Res.string.charging)})" else ""}"
    }

    override fun hasInternetPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    override fun deviceRealSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Pair(
                windowManager.currentWindowMetrics.bounds.width(),
                windowManager.currentWindowMetrics.bounds.height()
            )
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            Pair(size.x, size.y)
        }
    }

    override fun logcatMessages(level: String): List<String> {
        return try {
            val log = ProcessBuilder("logcat", "-d", "-T", "100", "--pid=${Process.myPid()}", "*:$level")
                .redirectErrorStream(true)
                .start()

            log.inputStream.bufferedReader().use { it.readLines() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun enableLogcat(level: String) {
        val logcatFile = File(OpenMWPaths.ACTIVE_CONFIG + "/openmw_logcat.txt")
        if (logcatFile.exists()) {
            logcatFile.delete()
        }

        val processBuilder = ProcessBuilder()
        val commandToExecute = arrayOf("/system/bin/sh", "-c", "logcat *:$level -d -f ${OpenMWPaths.ACTIVE_CONFIG}/openmw_logcat.txt")
        processBuilder.command(*commandToExecute)
        processBuilder.redirectErrorStream(true)
        processBuilder.start()
    }

    override fun readProcessOutput(vararg command: String): String {
        return try {
            val process = ProcessBuilder(*command).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            process.waitFor()
            output.toString()
        } catch (e: Exception) {
            "$e"
        }
    }
}
