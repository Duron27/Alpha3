package org.alpha3.launcher.files
import io.github.vinceglb.filekit.PlatformFile

expect fun listFilesInDirectory(path: String): List<String>
expect fun isFile(path: String): Boolean
expect fun isDirectory(path: String): Boolean
expect fun copyDirectory(source: String, destination: String): Boolean
expect fun copyPlatformFileToDirectory(source: PlatformFile, destination: String): Boolean

