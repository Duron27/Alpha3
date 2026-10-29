package org.alpha3.launcher.crash

/** A file to put in a zip: the name inside the archive and its content. */
class ZipSource(val name: String, val bytes: ByteArray)

/** Writes a zip archive at [destinationPath]; false when it could not be written. */
expect fun writeZip(destinationPath: String, entries: List<ZipSource>): Boolean

/** The local date and time as it goes in a file name, "2026-10-18_21-40-12". */
expect fun fileTimestamp(): String
