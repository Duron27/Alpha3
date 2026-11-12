package org.openmw

import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Files read as root on a rooted device: one `su` shell, started on the first read and kept, so a refresh is a line
 * written and read rather than a process. The GPU's load and clock nodes need it where SELinux keeps them from apps
 * (Adreno's kgsl on LineageOS, Mali's on most ROMs). No su, or root refused (or not granted within the wait, the root
 * manager's prompt): never asked again in this process.
 */
object RootShell {
    private const val TAG = "RootShell"
    private const val END = "<<a3-end>>"
    private val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/su/bin/su")

    private var process: Process? = null
    private val lines = LinkedBlockingQueue<String>()
    @Volatile private var refused = false

    @Synchronized
    fun read(path: String): String {
        if (refused || '\'' in path) return ""
        val shell = process ?: start() ?: return ""
        return runCatching {
            shell.outputStream.write("cat '$path' 2>/dev/null; echo '$END'\n".toByteArray())
            shell.outputStream.flush()
            val out = StringBuilder()
            while (true) {
                val line = lines.poll(2, TimeUnit.SECONDS) ?: return@runCatching giveUp("no answer")
                if (line == END) break
                out.append(line).append('\n')
            }
            out.toString().trim()
        }.getOrElse { giveUp(it.toString()) }
    }

    private fun start(): Process? {
        if (suPaths.none { File(it).exists() }) {
            refused = true
            return null
        }
        return runCatching {
            val started = ProcessBuilder("su").redirectErrorStream(true).start()
            Thread({
                runCatching { started.inputStream.bufferedReader().forEachLine { lines.put(it) } }
            }, "RootShell").apply { isDaemon = true }.start()
            // the root manager may ask the user first: room for that, then the shell must be root
            started.outputStream.write("id -u; echo '$END'\n".toByteArray())
            started.outputStream.flush()
            val uid = lines.poll(30, TimeUnit.SECONDS)
            val end = uid?.let { lines.poll(2, TimeUnit.SECONDS) }
            if (uid?.trim() != "0" || end != END) {
                started.destroy()
                giveUp("not root ($uid)")
                null
            } else {
                Log.i(TAG, "root shell open: GPU nodes read through it")
                process = started
                started
            }
        }.getOrElse { giveUp(it.toString()); null }
    }

    private fun giveUp(why: String): String {
        if (!refused) Log.i(TAG, "no root reads: $why")
        refused = true
        process?.destroy()
        process = null
        lines.clear()
        return ""
    }
}
