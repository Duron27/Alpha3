package org.alpha3.launcher.files

import platform.Foundation.*

actual fun writeTextFile(path: String, text: String): Boolean {
    val nsText = NSString.create(string = text)
    val data = nsText.dataUsingEncoding(NSUTF8StringEncoding) ?: return false

    return NSFileManager.defaultManager.createFileAtPath(
        path,
        data,
        null
    )
}


