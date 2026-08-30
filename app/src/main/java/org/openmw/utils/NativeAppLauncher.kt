package org.openmw.utils

import android.system.Os
import android.util.Log
import org.openmw.Constants
import java.io.File

object NativeAppLauncher {
    private const val TAG = "NativeAppLauncher"

    external fun launchIntMain(libName: String, args: Array<String>): Int

    /**
     * Set up process-level environment variables for OpenMW paths.
     * This avoids needing getPathToJni JNI calls in isolated processes.
     */
    fun setupNativePaths(filesDir: File) {
        try {
            val globalPath = filesDir.parent ?: ""
            val userPath = Constants.USER_FILE_STORAGE

            Os.setenv("OPENMW_ANDROID_GLOBAL_PATH", globalPath, true)
            Os.setenv("OPENMW_ANDROID_USER_PATH", userPath, true)
            Log.d(TAG, "Native paths set: GLOBAL=$globalPath, USER=$userPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set native path env vars", e)
        }
    }

    fun launchIniImporter(gameFilesPath: String, configPath: String): Int {
        val args = arrayOf(
            "openmw-iniimporter",
            "-v",
            "--game-files",
            "$gameFilesPath/Morrowind.ini",
            configPath
        )
        Log.d(TAG, "Launching IniImporter with args: ${args.joinToString(" ")}")
        return try {
            launchIntMain("libopenmw-iniimporter.so", args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch IniImporter", e)
            -1
        }
    }

    /**
     * Launches the NavMesh tool. 
     * Note: configPath should be the directory containing openmw.cfg, or the file itself
     */
    fun launchNavMesh(configPath: String): Int {
        val args = arrayOf(
            "openmw-navmeshtool",
            "-v",
            "--config",
            configPath
        )
        Log.d(TAG, "Launching NavMesh with args: ${args.joinToString(" ")}")
        return try {
            launchIntMain("libopenmw-navmeshtool.so", args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch NavMesh", e)
            -1
        }
    }
}
