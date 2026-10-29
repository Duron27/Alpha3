package org.openmw

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import org.alpha3.launcher.crash.TombstoneParser
import org.alpha3.launcher.paths.OpenMWPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects what Android knows about how the previous run ended.
 *
 * A native crash is reported twice: the process writes its own report while it dies (cpp/Alpha3/BackTrace.cpp, to
 * crash_report.txt) and the system writes a tombstone, which an app can only read afterwards through
 * ApplicationExitInfo. This reads it on the next start and keeps the raw tombstone (tombstone_last.pb) and the exit
 * record with a short summary (tombstone_last.txt). The crash viewer (org.alpha3.launcher.crash) shows both next to
 * crash_report.txt.
 */
object CrashReports {
    private const val TAG = "CrashReports"
    private const val PREFS = "crash_reports"
    private const val KEY_LAST = "last_exit_timestamp"

    /** Exits worth a report: not the user closing the app or the system tidying up. */
    private val interesting = setOf(
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    fun collectAsync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        Thread({
            runCatching { collect(context) }.onFailure { Log.w(TAG, "Could not read the last exit", it) }
        }, "crash-reports").start()
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by the system for memory)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN($reason)"
    }

    private fun collect(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val handled = prefs.getLong(KEY_LAST, 0L)
        // Newest first
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .firstOrNull { it.reason in interesting && it.timestamp > handled }
            ?: return
        prefs.edit().putLong(KEY_LAST, exit.timestamp).apply()

        val directory = File(OpenMWPaths.ACTIVE_CONFIG)
        directory.mkdirs()
        val text = StringBuilder()
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        text.appendLine("========== System exit record (ApplicationExitInfo) ==========")
        text.appendLine("Reason: ${reasonName(exit.reason)}")
        text.appendLine("Time: ${clock.format(Date(exit.timestamp))}")
        text.appendLine("Pid: ${exit.pid}, status: ${exit.status}, importance: ${exit.importance}")
        text.appendLine("Memory at exit: pss ${exit.pss} kB, rss ${exit.rss} kB")
        text.appendLine("Description: ${exit.description}")

        val trace = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || exit.reason == ApplicationExitInfo.REASON_ANR) {
            runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
        } else null

        if (trace != null && trace.isNotEmpty()) {
            if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                File(directory, "tombstone_last.pb").writeBytes(trace)
                text.appendLine("---- Tombstone (${trace.size} bytes, raw copy in tombstone_last.pb) ----")
                TombstoneParser.parse(trace)?.let { tombstone ->
                    text.appendLine("Signal: ${tombstone.signalName} (${tombstone.signalNumber}), ${tombstone.signalCodeName}")
                    tombstone.faultAddress?.let { text.appendLine("Fault address: 0x${it.toString(16)}") }
                    if (tombstone.abortMessage.isNotEmpty()) text.appendLine("Abort message: ${tombstone.abortMessage}")
                    tombstone.crashingThread?.let { thread ->
                        text.appendLine("Crashed thread: ${thread.name} (tid ${thread.id})")
                        thread.frames.take(12).forEachIndexed { index, frame ->
                            text.appendLine("  #$index ${frame.fileName.substringAfterLast('/')} + 0x${frame.relPc.toString(16)} ${frame.functionName}")
                        }
                    }
                }
            } else {
                text.appendLine("---- ANR trace ----")
                text.append(String(trace, Charsets.UTF_8))
            }
        }
        text.appendLine("========== End of system exit record ==========")

        File(directory, "tombstone_last.txt").writeText(text.toString())

        Log.i(TAG, "Saved the exit record of the last run (${reasonName(exit.reason)}) to ${directory}/tombstone_last.txt")
    }
}
