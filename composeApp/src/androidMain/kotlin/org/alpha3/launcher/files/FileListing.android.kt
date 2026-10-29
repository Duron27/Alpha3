package org.alpha3.launcher.files

import io.github.vinceglb.filekit.PlatformFile
import java.io.File

actual fun listFilesInDirectory(path: String): List<String> {
    val dir = File(path)
    return dir.listFiles()?.map { it.absolutePath } ?: emptyList()
}

actual fun isFile(path: String): Boolean = File(path).isFile
actual fun isDirectory(path: String): Boolean = File(path).isDirectory

actual fun copyDirectory(source: String, destination: String): Boolean {
    val src = File(source)
    val dst = File(destination)
    if (!src.exists()) return false
    
    if (src.isDirectory) {
        if (!dst.exists()) dst.mkdirs()
        val children = src.list() ?: return true
        for (child in children) {
            if (!copyDirectory(File(src, child).absolutePath, File(dst, child).absolutePath)) {
                return false
            }
        }
        return true
    } else {
        return try {
            src.copyTo(dst, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }
}

actual fun copyPlatformFileToDirectory(source: PlatformFile, destination: String): Boolean {
    return copyDirectory(source.toString(), destination)
}
