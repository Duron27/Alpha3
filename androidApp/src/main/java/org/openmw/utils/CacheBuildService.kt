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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.alpha3.launcher.paths.OpenMWPaths
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.utils.DistantMeshPrepassProgress
import org.alpha3.launcher.utils.DistantMeshPrepassResult
import org.alpha3.launcher.utils.DistantMeshPrepassRunner
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * The distant mesh pass of the cache builder, in a process of its own (android:process=":cache"): it loads the stock
 * engine's library for this alone, and the process ends with the run, which lets go of all of it. Progress and the
 * result go back to the launcher through the [ResultReceiver] of the start intent.
 */
class CacheBuildService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (libraryLoaded) cancelNative()
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        val receiver = intent?.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        val arguments = intent?.getStringArrayExtra(EXTRA_ARGUMENTS)
        if (receiver == null || arguments == null || running) {
            receiver?.send(RESULT_DONE, Bundle().apply { putInt(KEY_CODE, CODE_BUSY) })
            return START_NOT_STICKY
        }
        running = true
        thread(name = "Alpha3Cache") {
            val code = try {
                UpstreamEngine.prepare(this)
                UpstreamEngine.exportCachePaths(this)
                Os.setenv("OPENMW_ANDROID_GLOBAL_PATH", UpstreamEngine.globalPath(this), true)
                Os.setenv("OPENMW_ANDROID_USER_PATH", OpenMWPaths.USER_FILE_STORAGE, true)
                for (lib in arrayOf("SDL2", "c++_shared", "openal", "openmw_upstream")) System.loadLibrary(lib)
                libraryLoaded = true
                runNative(arguments) { done, total, made, notMade ->
                    receiver.send(RESULT_PROGRESS, Bundle().apply { putIntArray(KEY_PROGRESS, intArrayOf(done, total, made, notMade)) })
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Cache build failed", e)
                CODE_FAILED
            }
            receiver.send(RESULT_DONE, Bundle().apply { putInt(KEY_CODE, code) })
            stopSelf(startId)
            // the engine's library and everything it made go with the process
            Process.killProcess(Process.myPid())
        }
        return START_NOT_STICKY
    }

    /** Called from native code by name, so the release build's shrinker must not rename it. */
    @Keep
    fun interface Listener {
        @Keep
        fun onProgress(done: Int, total: Int, made: Int, notMade: Int)
    }

    companion object {
        private const val TAG = "CacheBuildService"
        const val ACTION_CANCEL = "org.openmw.CACHE_BUILD_CANCEL"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_ARGUMENTS = "arguments"
        const val RESULT_PROGRESS = 1
        const val RESULT_DONE = 2
        const val KEY_PROGRESS = "progress"
        const val KEY_CODE = "code"
        // what runNative returns (cachebuild.cpp), and two of the service's own
        const val CODE_DONE = 0
        const val CODE_CANCELLED = 1
        const val CODE_OFF = 2
        const val CODE_FAILED = 3
        const val CODE_BUSY = 4

        @Volatile private var running = false
        @Volatile private var libraryLoaded = false

        @JvmStatic private external fun runNative(arguments: Array<String>, listener: Listener): Int

        @JvmStatic private external fun cancelNative()
    }
}

/** Starts [CacheBuildService] with the engine's own arguments and waits for its result. */
class DistantMeshPrepass(private val context: Context) : DistantMeshPrepassRunner {
    @Volatile private var started = false

    override suspend fun run(onProgress: (DistantMeshPrepassProgress) -> Unit): DistantMeshPrepassResult {
        // as the game gets them (EngineActivity.getArguments): the resources, then the profile's config folder
        val profile = if (Profiles.isDefault) emptyList() else Profiles.engineArguments()
        val arguments = (listOf("--resources", UpstreamEngine.resourcesDir) + profile).toTypedArray()
        val code = suspendCancellableCoroutine { continuation ->
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    when (resultCode) {
                        CacheBuildService.RESULT_PROGRESS -> resultData?.getIntArray(CacheBuildService.KEY_PROGRESS)?.let {
                            onProgress(DistantMeshPrepassProgress(it[0], it[1], it[2], it[3]))
                        }
                        CacheBuildService.RESULT_DONE -> if (continuation.isActive) {
                            continuation.resume(resultData?.getInt(CacheBuildService.KEY_CODE) ?: CacheBuildService.CODE_FAILED)
                        }
                    }
                }
            }
            started = true
            context.startService(Intent(context, CacheBuildService::class.java).apply {
                putExtra(CacheBuildService.EXTRA_RECEIVER, receiver)
                putExtra(CacheBuildService.EXTRA_ARGUMENTS, arguments)
            })
            continuation.invokeOnCancellation { cancel() }
        }
        started = false
        return withContext(Dispatchers.Default) {
            when (code) {
                CacheBuildService.CODE_DONE -> DistantMeshPrepassResult.Done
                CacheBuildService.CODE_CANCELLED -> DistantMeshPrepassResult.Cancelled
                CacheBuildService.CODE_OFF -> DistantMeshPrepassResult.Off
                else -> DistantMeshPrepassResult.Failed
            }
        }
    }

    override fun cancel() {
        // only to a run that was started: the intent would otherwise start the service's process for nothing
        if (!started) return
        context.startService(Intent(context, CacheBuildService::class.java).setAction(CacheBuildService.ACTION_CANCEL))
    }
}
