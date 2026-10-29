package org.alpha3.launcher.files

import java.io.File

actual fun createTestFiles(path: String) {
    val dir = File(path)
    if (!dir.exists()) dir.mkdirs()

    val files = listOf(
        "one.txt" to "Hello from Android file 1",
        "two.txt" to "Hello from Android file 2",
        "three.txt" to "Hello from Android file 3"
    )

    files.forEach { (name, content) ->
        File(dir, name).writeText(content)
    }
}
