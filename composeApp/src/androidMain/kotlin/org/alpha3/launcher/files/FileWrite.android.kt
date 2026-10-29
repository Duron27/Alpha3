package org.alpha3.launcher.files

import java.io.File

actual fun writeTextFile(path: String, text: String): Boolean {
    return try {
        File(path).writeText(text)
        true
    } catch (e: Exception) {
        false
    }
}


