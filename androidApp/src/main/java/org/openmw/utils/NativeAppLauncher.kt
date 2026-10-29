package org.openmw.utils

import android.system.Os
import android.util.Log
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import java.io.File

object NativeAppLauncher {
    private const val TAG = "NativeAppLauncher"

    external fun launchIntMain(libName: String, args: Array<String>): Int

    /**
     * Calls `int [symbol](int argc, char** argv, output, user)` of [libName] in this process and returns what it
     * passed to `output`. The exit code is written to codeOut[0]. Calls from different threads can overlap.
     */
    external fun runWithOutput(libName: String, symbol: String, args: Array<String>, codeOut: IntArray): String

    /**
     * Set up process-level environment variables for OpenMW paths.
     * This avoids needing getPathToJni JNI calls in isolated processes.
     */
    fun setupNativePaths(filesDir: File) {
        try {
            val globalPath = filesDir.parent ?: ""
            val userPath = OpenMWPaths.USER_FILE_STORAGE

            Os.setenv("OPENMW_ANDROID_GLOBAL_PATH", globalPath, true)
            Os.setenv("OPENMW_ANDROID_USER_PATH", userPath, true)
            Log.d(TAG, "Native paths set: GLOBAL=$globalPath, USER=$userPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set native path env vars", e)
        }
    }

    /**
     * Launches the NavMesh tool. 
     * Note: configPath should be the directory containing openmw.cfg, or the file itself
     */
    fun launchNavMesh(configPath: String): Int {
        val args = arrayOf("openmw-navmeshtool", "-v") + Profiles.engineArguments(configPath)
        Log.d(TAG, "Launching NavMesh with args: ${args.joinToString(" ")}")
        return try {
            launchIntMain("libopenmw-navmeshtool.so", args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch NavMesh", e)
            -1
        }
    }
}
