package org.openmw.utils

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import org.alpha3.launcher.ui.controls.UIStateManager

@OptIn(InternalCoroutinesApi::class)
class NavMeshService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d("NavMeshService", "Service onCreate in process: ${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configPath = intent?.getStringExtra("configPath")
        Log.d("NavMeshService", "onStartCommand received configPath: $configPath")

        if (configPath != null) {
            serviceScope.launch {
                try {
                    Log.d("NavMeshService", "Starting NavMesh native execution...")

                    // 1. Load the bridge library
                    System.loadLibrary("Alpha3")

                    // 2. Initialize native paths via environment variables (cleaner for separate process)
                    NativeAppLauncher.setupNativePaths(filesDir)

                    // 3. Load dependencies
                    val libs = arrayOf("SDL2", "GLESv3", "c++_shared", "openal", "openmw-navmeshtool")
                    libs.forEach { lib ->
                        try {
                            System.loadLibrary(lib)
                            Log.d("NavMeshService", "Loaded $lib")
                        } catch (e: UnsatisfiedLinkError) {
                            Log.w("NavMeshService", "Library $lib already loaded or not found ($e)")
                        }
                    }

                    val result = NativeAppLauncher.launchNavMesh(configPath)

                    Log.d("NavMeshService", "Native NavMesh returned: $result")

                } catch (e: Exception) {
                    Log.e("NavMeshService", "Error in NavMesh execution", e)
                } finally {
                    UIStateManager.useNavmesh = false
                    Log.d("NavMeshService", "NavMesh finished, stopping service.")
                    stopSelf(startId)
                }
            }
        } else {
            Log.e("NavMeshService", "Error: configPath was null, cannot start NavMesh.")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
