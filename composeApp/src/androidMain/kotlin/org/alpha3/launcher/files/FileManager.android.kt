package org.alpha3.launcher.files

import java.io.File

actual fun deleteFile(path: String): Boolean {
    val f = File(path)
    return f.exists() && f.delete()
}

