package org.alpha3.launcher.files

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.Foundation.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
actual fun deleteFile(path: String): Boolean {
    val fm = NSFileManager.defaultManager
    val errorPtr = nativeHeap.alloc<ObjCObjectVar<NSError?>>()
    val success = fm.removeItemAtPath(path, errorPtr.ptr)
    return success
}
