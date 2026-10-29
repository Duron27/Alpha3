package org.alpha3.launcher.files

import platform.Foundation.*

actual fun readTextFile(path: String): String? {
    val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return null
    return NSString.create(data, NSUTF8StringEncoding) as String?
}

actual fun readTextFile2(path: String): String = readTextFile(path) ?: ""

