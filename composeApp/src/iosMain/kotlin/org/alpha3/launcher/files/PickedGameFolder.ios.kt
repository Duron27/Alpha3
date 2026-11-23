@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher.files

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.cinterop.ExperimentalForeignApi
import org.alpha3.launcher.io.File
import platform.Foundation.NSFileManager

/**
 * Stands in for Morrowind.ini when only "Data Files" was picked and the real one could not be read. It has no settings,
 * which the ini import takes to mean "keep the shipped ones" (IniImporter.mergeFallback).
 */
private const val PLACEHOLDER_INI =
    "; Made by Alpha3: only the Data Files folder was selected, so there was no Morrowind.ini to copy.\r\n[General]\r\n"

/** The game folder made below the app's Documents for a picked "Data Files" folder whose parent cannot be read. */
private const val GAME_FOLDER_NAME = "Morrowind"

/** The plugins of the game sit directly in it, and it has neither Morrowind.ini nor a "Data Files" of its own. */
private fun isDataFilesFolder(path: String): Boolean {
    val names = listFilesInDirectory(path).map { it.substringAfterLast('/').lowercase() }
    return "morrowind.esm" in names && "morrowind.ini" !in names && "data files" !in names
}

/**
 * Copies what the system folder picker returned into [documents] and returns the game folder to use: the one holding
 * Morrowind.ini and "Data Files".
 *
 * People often pick "Data Files" itself, since that is where the .esm files are. Then the parent is the game folder:
 * "Data Files" and ../Morrowind.ini are copied into `<documents>/<parent name>`. On a device the picker's access usually
 * stops at the folder picked, so ../Morrowind.ini may not be readable; then the game folder is `<documents>/Morrowind`
 * with a placeholder ini, and OpenMW's own fallback values are used.
 */
fun copyPickedGameFolder(picked: PlatformFile, documents: String): String {
    val url = picked.nsUrl
    val started = url.startAccessingSecurityScopedResource()
    try {
        val source = url.path!!
        if (!isDataFilesFolder(source)) {
            val destination = "$documents/${picked.name}"
            if (!copyDirectory(source, destination)) println("copyPickedGameFolder: copy of ${picked.name} was incomplete")
            return destination
        }

        // The game folder is the parent when it has the Morrowind.ini (it is then copied under its own name)
        val parent = source.substringBeforeLast('/')
        val parentIni = "$parent/Morrowind.ini"
        val hasParentIni = isFile(parentIni)
        println("copyPickedGameFolder: ${picked.name} is a Data Files folder; ../Morrowind.ini ${if (hasParentIni) "found" else "not readable"}")
        val game = "$documents/${if (hasParentIni) parent.substringAfterLast('/') else GAME_FOLDER_NAME}"
        if (!copyDirectory(source, "$game/Data Files")) println("copyPickedGameFolder: copy of Data Files was incomplete")
        val ini = "$game/Morrowind.ini"
        if (hasParentIni && copyDirectory(parentIni, ini)) {
            println("copyPickedGameFolder: copied ../Morrowind.ini")
        } else if (!isFile(ini)) {
            NSFileManager.defaultManager.createDirectoryAtPath(game, true, null, null)
            File(ini).writeText(PLACEHOLDER_INI)
            println("copyPickedGameFolder: no Morrowind.ini to copy, wrote a placeholder")
        }
        return game
    } finally {
        if (started) url.stopAccessingSecurityScopedResource()
    }
}
