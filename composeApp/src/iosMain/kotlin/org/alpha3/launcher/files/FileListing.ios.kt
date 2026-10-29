package org.alpha3.launcher.files

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.*
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual fun listFilesInDirectory(path: String): List<String> {
    val fm = NSFileManager.defaultManager
    val contents = fm.contentsOfDirectoryAtPath(path, null) ?: return emptyList()
    return contents.map { "$path/${it.toString()}" }
}

@OptIn(ExperimentalForeignApi::class)
actual fun isFile(path: String): Boolean {
    val fm = NSFileManager.defaultManager
    return memScoped {
        val isDir = alloc<BooleanVar>()
        val exists = fm.fileExistsAtPath(path, isDir.ptr)
        exists && !isDir.value
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun isDirectory(path: String): Boolean {
    val fm = NSFileManager.defaultManager
    return memScoped {
        val isDir = alloc<BooleanVar>()
        val exists = fm.fileExistsAtPath(path, isDir.ptr)
        exists && isDir.value
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun copyDirectory(source: String, destination: String): Boolean {
    val fm = NSFileManager.defaultManager

    // The picker can hand back a folder that already lives in the app container (always the case in the
    // simulator's Files UI). Copying it onto itself would delete the source below, so leave it as is.
    if (sameLocation(source, destination)) return true

    // NSFileManager.copyItemAtPath fails if destination exists, so merge/overwrite by hand.
    // Best effort: one unreadable file (e.g. .DS_Store) must not abort the rest of the folder.
    if (isDirectory(source)) {
        if (!fm.fileExistsAtPath(destination)) {
            if (!fm.createDirectoryAtPath(destination, true, null, null)) {
                println("copyDirectory: cannot create $destination")
                return false
            }
        }

        val contents = fm.contentsOfDirectoryAtPath(source, null) ?: return true
        var ok = true
        for (item in contents) {
            val name = item.toString()
            if (name.startsWith(".")) continue // Finder metadata (.DS_Store etc.)
            if (!copyDirectory("$source/$name", "$destination/$name")) ok = false
        }
        return ok
    } else {
        if (fm.fileExistsAtPath(destination)) {
            fm.removeItemAtPath(destination, null)
        }
        val copied = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val result = fm.copyItemAtPath(source, destination, error.ptr)
            if (!result) println("copyDirectory: failed $source -> $destination: ${error.value?.localizedDescription}")
            result
        }
        return copied
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun copyPlatformFileToDirectory(source: PlatformFile, destination: String): Boolean {
    val url = source.nsUrl
    val start = url.startAccessingSecurityScopedResource()
    val success = copyDirectory(url.path!!, destination)
    if (start) url.stopAccessingSecurityScopedResource()
    return success
}


private fun sameLocation(a: String, b: String): Boolean {
    fun canonical(path: String) = NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath?.path ?: path
    return canonical(a) == canonical(b)
}
