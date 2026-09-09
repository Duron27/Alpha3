package org.openmw.utils

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import org.openmw.Constants
import org.openmw.ui.page.mod.ModUIState
import java.io.File

@OptIn(InternalCoroutinesApi::class)
class IniImporterService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gameFilesPath = intent?.getStringExtra("gameFilesPath")
        
        Log.d("IniImporterService", "onStartCommand in process: ${android.os.Process.myPid()}")

        if (gameFilesPath != null) {
            serviceScope.launch {
                try {
                    Log.d("IniImporterService", "Starting IniImporter in separate process...")
                    
                    // 1. Load bridge
                    System.loadLibrary("Alpha3")

                    // 2. Initialize native paths via environment variables
                    NativeAppLauncher.setupNativePaths(filesDir)

                    // 3. Load required libraries
                    val libs = arrayOf("SDL2", "GLESv3", "c++_shared", "openal", "openmw-iniimporter")
                    libs.forEach { lib ->
                        try {
                            System.loadLibrary(lib)
                        } catch (e: UnsatisfiedLinkError) {
                            Log.w("IniImporterService", "Library $lib load result: ${e.message}")
                        }
                    }

                    NativeAppLauncher.launchIniImporter(gameFilesPath, Constants.OPENMW_CFG)
                    
                    val cfgFile = File(Constants.OPENMW_CFG)
                    if (cfgFile.exists()) {
                        val linesToDisable = setOf(
                            "fallback-archive=morrowind.bsa",
                            "fallback-archive=tribunal.bsa",
                            "fallback-archive=bloodmoon.bsa",
                            "content=morrowind.esm",
                            "content=tribunal.esm",
                            "content=bloodmoon.esm"
                        )
                        val updatedLines = cfgFile.readLines().map { line ->
                            val trimmed = line.trim()
                            if (linesToDisable.contains(trimmed.lowercase())) {
                                ";$line"
                            } else {
                                line
                            }
                        }
                        cfgFile.writeText(updatedLines.joinToString("\n") + "\n")
                    }

                    ModUIState.refreshUI()
                    Log.d("IniImporterService", "IniImporter finished.")
                } catch (e: Exception) {
                    Log.e("IniImporterService", "Error running IniImporter", e)
                } finally {
                    val finishedIntent = Intent("org.openmw.ACTION_INI_IMPORT_FINISHED")
                    sendBroadcast(finishedIntent)
                    stopSelf(startId)
                }
            }
        } else {
            Log.e("IniImporterService", "Missing arguments (gameFilesPath or configPath)")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
