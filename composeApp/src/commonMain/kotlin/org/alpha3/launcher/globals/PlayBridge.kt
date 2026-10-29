package org.alpha3.launcher.globals

import androidx.compose.runtime.mutableStateListOf

object PlayBridge {
    var onPlay: (() -> Unit)? = null
    var onResetSettings: (() -> Unit)? = null
    val logs = mutableStateListOf<String>()

    fun addLog(line: String) {
        if (logs.size > 500) logs.removeAt(0)
        logs.add(line)
    }

    fun addLogs(lines: List<String>) {
        logs.addAll(lines)
        while (logs.size > 500) {
            logs.removeAt(0)
        }
    }

    fun clearLogs() {
        logs.clear()
    }
}
