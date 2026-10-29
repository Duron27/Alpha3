package org.alpha3.launcher.files

import java.io.File

actual fun readTextFile(path: String): String? {
    val f = File(path)
    if (!f.exists()) return null
    return f.readText()
}

actual fun readTextFile2(path: String): String =
    File(path).takeIf { it.exists() }?.readText() ?: ""

