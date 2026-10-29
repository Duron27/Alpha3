package org.alpha3.launcher.crash

import org.alpha3.launcher.paths.OpenMWPaths
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

actual fun readOnlineSymbols(buildId: String): String? {
    // The build id goes into a path and a URL: only what a build id is made of
    if (buildId.isEmpty() || !buildId.all { it in '0'..'9' || it in 'a'..'f' }) return null
    val cached = File(OpenMWPaths.USER_CONFIG, "symbols/$buildId.syms")
    if (cached.isFile && cached.length() > 0) return runCatching { cached.readText() }.getOrNull()
    return runCatching {
        val connection = URL("$SYMBOL_PACKAGE_URL/$buildId/$buildId.syms").openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            // A real table starts with its header; anything else (a login page, an error) is not kept
            if (!text.startsWith("# alpha3 symbols")) return null
            cached.parentFile?.mkdirs()
            cached.writeText(text)
            text
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
