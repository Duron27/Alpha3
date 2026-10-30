@file:OptIn(InternalCoroutinesApi::class, ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)

package org.openmw

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.alpha3.launcher.utils.FileBrowserMode
import org.alpha3.launcher.utils.PlatformFeatures
import org.alpha3.launcher.utils.Uri
import org.openmw.utils.UserManageAssets
import java.io.File
import java.lang.ref.WeakReference
import org.openmw.utils.FileBrowserMode as AndroidFileBrowserMode

/** Android implementation of the shared [PlatformFeatures]. */
class AndroidPlatformFeatures(private val app: Application) : PlatformFeatures() {

    private var activityRef = WeakReference<Activity>(null)
    private val activity: Activity? get() = activityRef.get()

    private val assets get() = UserManageAssets(app)

    init {
        // The shared screens only know "the host"; remember whichever Activity is in front.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { activityRef = WeakReference(a) }
            override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) { activityRef = WeakReference(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (activityRef.get() === a) activityRef.clear() }
        })
    }

    override fun toast(msg: String, isLong: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(app, msg, if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    override fun startGame(isFinish: Boolean) {
        val host = activity
        val intent = Intent(host ?: app, EngineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        (host ?: app).startActivity(intent)
        if (isFinish) host?.finish()
    }

    override fun hasInternetConnection(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun restartLauncher() {
        val host = activity ?: app
        host.startActivity(Intent(host, MainActivity::class.java).apply {
            if (host === app) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    override fun finishHost() {
        activity?.finish()
    }

    @Suppress("DEPRECATION")
    override fun showSoftKeyboard() {
        val host = activity ?: return
        val inputMethodManager = host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        host.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun prepareResources() {
        assets.resourcePrepare()
        // The stock engine's resources are copied here too, so it happens while the launch button shows its progress;
        // EngineActivity's own call then finds them current
        if (org.alpha3.launcher.ui.controls.UIStateManager.useUpstreamEngine) org.openmw.utils.UpstreamEngine.prepare(app)
    }
    override fun resetUserResources() = assets.resetUserResources()
    override fun resetUserConfig() = assets.resetUserConfig()
    override fun resetUI() = assets.resetUI()

    override fun runNavMesh() = runNavMesh(app)

    override val modDownloaderHost get() = org.openmw.mods.AndroidModDownloaderHost
    override val toolFs get() = org.alpha3.launcher.tools.openmw.AndroidToolFs
    override val toolPlatform get() = org.alpha3.launcher.tools.openmw.androidToolPlatform()
    override val supportsTerminal get() = true
    @Composable override fun Terminal() = org.openmw.utils.Terminal()
    @Composable override fun UqmDownloads() = org.openmw.utils.UqmDownloads()
    // libkram.so is only built with the fork (see cpp/CMakeLists.txt); stock OpenMW has the texture pre-pass instead
    override val kramRunner get() = if (BuildConfig.OPENMW_FORK) org.openmw.utils.AndroidKramRunner() else null
    override val texturePrepass: org.alpha3.launcher.utils.TexturePrepassRunner by lazy { org.openmw.utils.TexturePrepass(app) }
    override val distantMeshPrepass: org.alpha3.launcher.utils.DistantMeshPrepassRunner by lazy { org.openmw.utils.DistantMeshPrepass(app) }
    override val plugins: org.alpha3.launcher.utils.PluginManager by lazy { org.openmw.plugins.AndroidPluginManager(app) }
    override val gpuDrivers: org.alpha3.launcher.utils.GpuDriverManager by lazy { org.openmw.utils.AndroidGpuDriverManager(app) }

    @Composable
    override fun FileBrowserPopup(
        initialDirectory: String,
        mode: FileBrowserMode,
        onDismiss: () -> Unit,
        onFileSelected: ((String) -> Unit)?,
        onFolderSelected: ((String) -> Unit)?
    ) = org.openmw.utils.FileBrowserPopup(
        initialDirectory = File(initialDirectory),
        onDismiss = onDismiss,
        onFileSelected = onFileSelected?.let { cb -> { file: File -> cb(file.path) } },
        onFolderSelected = onFolderSelected?.let { cb -> { file: File -> cb(file.path) } },
        mode = if (mode == FileBrowserMode.FOLDER) AndroidFileBrowserMode.FOLDER else AndroidFileBrowserMode.FILE
    )

    @Composable
    override fun InitialDirectorySelection(onSelect: (String) -> Unit, onDismiss: () -> Unit): Unit =
        org.openmw.utils.InitialDirectorySelection(onSelect = { onSelect(it.path) }, onDismiss = onDismiss)

    @Composable
    override fun CustomIconPickerButton(
        buttonId: Int,
        buttonUri: MutableState<Uri?>,
        containerWidth: Float,
        containerHeight: Float
    ): Unit = org.openmw.utils.CustomIconPickerButton(LocalContext.current, buttonId, buttonUri, containerWidth, containerHeight)

}
