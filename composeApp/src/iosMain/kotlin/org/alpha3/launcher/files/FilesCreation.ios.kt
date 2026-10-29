package org.alpha3.launcher.files

import platform.Foundation.*

actual fun createTestFiles(path: String) {
    val fm = NSFileManager.defaultManager

    // Create 3 simple text files
    val files = listOf(
        "one.txt" to "Hello from iOS file 1",
        "two.txt" to "Hello from iOS file 2",
        "three.txt" to "Hello from iOS file 3"
    )

    files.forEach { (name, content) ->
        val fullPath = "$path/$name"
        val data = (content as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        fm.createFileAtPath(fullPath, data, null)
    }
}
