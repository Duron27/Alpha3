package org.openmw.utils

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import android.system.Os
import android.util.Log
import androidx.annotation.Keep
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.utils.NavMeshBuildProgress
import org.alpha3.launcher.utils.NavMeshStatus
import kotlin.concurrent.thread

/**
 * navmesh.db for the mod list, in a process of its own (android:process=":navmesh"), which ends with the run.
 *
 * The stock engine: its library does navmeshtool's work (support/src/navmeshbuild.cpp, its JNI in androidmain.cpp), and
 * progress and the result go back to the launcher through the [ResultReceiver] of the start intent. The fork: its
 * libopenmw-navmeshtool.so, followed through navmeshtool.log.
 */
class NavMeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val receiver = intent?.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        val arguments = intent?.getStringArrayExtra(EXTRA_ARGUMENTS)
        val configPath = intent?.getStringExtra(EXTRA_CONFIG_PATH)
        if (running || (receiver == null || arguments == null) && configPath == null) {
            receiver?.send(RESULT_DONE, Bundle().apply { putInt(KEY_CODE, CODE_FAILED) })
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        thread(name = "NavMesh") {
            val code = try {
                if (receiver != null && arguments != null) runUpstream(arguments, receiver) else runFork(configPath!!)
            } catch (e: Throwable) {
                Log.e(TAG, "Navmesh failed", e)
                CODE_FAILED
            }
            Log.d(TAG, "Navmesh returned $code")
            receiver?.send(RESULT_DONE, Bundle().apply { putInt(KEY_CODE, code) })
            stopSelf(startId)
            // everything the engine's library made goes with the process
            Process.killProcess(Process.myPid())
        }
        return START_NOT_STICKY
    }

    private fun runUpstream(arguments: Array<String>, receiver: ResultReceiver): Int {
        UpstreamEngine.exportCachePaths(this)
        Os.setenv("OPENMW_ANDROID_GLOBAL_PATH", UpstreamEngine.globalPath(this), true)
        Os.setenv("OPENMW_ANDROID_USER_PATH", OpenMWPaths.USER_FILE_STORAGE, true)
        for (lib in arrayOf("SDL2", "c++_shared", "openal", "openmw_upstream")) System.loadLibrary(lib)
        return runNative(arguments) { phase, worldspacesDone, worldspaces, tilesDone, tiles ->
            receiver.send(RESULT_PROGRESS, Bundle().apply {
                putIntArray(KEY_PROGRESS, intArrayOf(phase, worldspacesDone, worldspaces, tilesDone, tiles))
            })
        }
    }

    private fun runFork(configPath: String): Int {
        System.loadLibrary("Alpha3")
        NativeAppLauncher.setupNativePaths(filesDir)
        for (lib in arrayOf("SDL2", "GLESv3", "c++_shared", "openal", "openmw-navmeshtool")) {
            try {
                System.loadLibrary(lib)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Library $lib already loaded or not found ($e)")
            }
        }
        return NativeAppLauncher.launchNavMesh(configPath)
    }

    /** Called from native code by name, so the release build's shrinker must not rename it. */
    @Keep
    fun interface Listener {
        @Keep
        fun onProgress(phase: Int, worldspacesDone: Int, worldspaces: Int, tilesDone: Int, tiles: Int)
    }

    companion object {
        private const val TAG = "NavMeshService"
        private const val EXTRA_RECEIVER = "receiver"
        private const val EXTRA_ARGUMENTS = "arguments"
        private const val EXTRA_CONFIG_PATH = "configPath"
        private const val RESULT_PROGRESS = 1
        private const val RESULT_DONE = 2
        private const val KEY_PROGRESS = "progress"
        private const val KEY_CODE = "code"
        // what runNative returns (Alpha3::buildNavMesh): 0 done, 1 cancelled, 2 out of space, 3 failed
        private const val CODE_FAILED = 3

        @Volatile private var running = false

        @JvmStatic private external fun runNative(arguments: Array<String>, listener: Listener): Int

        /**
         * Starts the service. The stock engine's run reports into [NavMeshStatus] as it goes; the fork's writes only
         * navmeshtool.log.
         */
        fun start(context: Context) {
            val intent = Intent(context, NavMeshService::class.java)
            if (!UIStateManager.useUpstreamEngine) {
                context.startService(intent.putExtra(EXTRA_CONFIG_PATH, OpenMWPaths.ACTIVE_CONFIG))
                return
            }
            // as the game gets them (EngineActivity.getArguments): the resources, then the profile's config folder
            val profile = if (Profiles.isDefault) emptyList() else Profiles.engineArguments()
            val arguments = (listOf("--resources", UpstreamEngine.resourcesDir) + profile).toTypedArray()
            NavMeshStatus.progress.value = NavMeshBuildProgress(0, 0, 0, 0, 0)
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    val last = NavMeshStatus.progress.value ?: NavMeshBuildProgress(0, 0, 0, 0, 0)
                    NavMeshStatus.progress.value = when (resultCode) {
                        RESULT_PROGRESS -> resultData?.getIntArray(KEY_PROGRESS)?.let {
                            NavMeshBuildProgress(it[0], it[1], it[2], it[3], it[4])
                        } ?: last
                        else -> last.copy(result = resultData?.getInt(KEY_CODE) ?: CODE_FAILED)
                    }
                }
            }
            context.startService(intent.putExtra(EXTRA_RECEIVER, receiver).putExtra(EXTRA_ARGUMENTS, arguments))
        }
    }
}
