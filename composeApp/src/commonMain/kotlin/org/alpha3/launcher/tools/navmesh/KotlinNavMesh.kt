// SPDX-License-Identifier: GPL-3.0-or-later
package org.alpha3.launcher.tools.navmesh

import org.alpha3.launcher.tools.NavMeshTool
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolFs
import org.alpha3.launcher.tools.openmw.ToolPlatform
import org.alpha3.launcher.tools.openmw.Vfs
import org.alpha3.launcher.tools.openmw.readOpenMW
import org.alpha3.launcher.utils.availableCpuCores
import org.alpha3.launcher.utils.currentTimeMillis

/** The Kotlin port of openmw-navmeshtool, run with the user's openmw.cfg. It writes the same progress log the native tool does. */
object KotlinNavMesh {
    /**
     * Generates [dbPath] for the load order in [cfg]. Progress lines go to [log] and, in the format the native tool
     * writes, to [logFile] so the navmesh progress screen can follow it. [threads] tiles are built at a time.
     */
    suspend fun run(
        fs: ToolFs,
        platform: ToolPlatform,
        cfg: String,
        dbPath: String,
        logFile: String? = null,
        threads: Int = availableCpuCores(),
        processInteriorCells: Boolean = false,
        log: (String) -> Unit = {},
    ) {
        val lines = ArrayList<String>()
        var lastWrite = 0L
        fun write(force: Boolean) {
            val file = logFile ?: return
            val now = currentTimeMillis()
            if (!force && now - lastWrite < 1000) return
            lastWrite = now
            try {
                fs.delete(file)
                val w = fs.openWrite(file)
                try { val b = lines.joinToString("\n", postfix = "\n").encodeToByteArray(); w.write(b, 0, b.size) } finally { w.close() }
            } catch (_: Exception) {
            }
        }
        val sink: (String) -> Unit = { line ->
            log(line)
            if (line.contains("navmesh tiles are generated")) {
                if (lines.isNotEmpty() && lines.last().contains("navmesh tiles are generated")) lines.removeAt(lines.size - 1)
            }
            lines.add(line)
            write(false)
        }
        try {
            val omw = readOpenMW(cfg, platform, fs)
            val codec = TextCodec.forName(omw.encoding)
            val vfs = Vfs(omw.data, omw.archives, codec, fs)
            val parent = dbPath.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) fs.mkdirs(parent)
            val job = NavMeshJob(omw.content, vfs, fs, codec, dbPath, processInteriorCells = processInteriorCells, threads = threads)
            runNavMeshJob(job, sink) { _: NavMeshTool.Message -> }
            sink("runNavMeshTool sucess")
        } catch (e: Exception) {
            sink("runNavMeshTool failed: ${e.message ?: e}")
            throw e
        } finally {
            write(true)
        }
    }
}
