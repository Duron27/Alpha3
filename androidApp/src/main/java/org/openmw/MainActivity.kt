package org.openmw

import org.alpha3.launcher.paths.OpenMWPaths
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.alpha3.launcher.mods.downloader.ModDatabase
import org.alpha3.launcher.ui.controls.UIStateManager
import org.alpha3.launcher.ui.navigation.RootNav
import org.alpha3.launcher.ui.theme.OpenMWTheme
import org.alpha3.launcher.ui.view.BackgroundAnimation
import org.alpha3.launcher.ui.view.MoeDialog
import org.alpha3.launcher.ui.view.NavmeshScreen
import org.alpha3.launcher.utils.SystemInfo
import org.alpha3.launcher.ui.view.updateResolutionInConfig
import org.openmw.utils.CaptureCrash
import org.alpha3.launcher.paths.Profiles
import org.openmw.utils.ConfigFileObserver
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.MyAlertDialog
import org.alpha3.launcher.mods.downloader.NxmDownloads
import org.openmw.utils.UpstreamEngine
import org.openmw.utils.UserManageAssets

@InternalCoroutinesApi
@ExperimentalMaterial3Api
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            proceedWithNextSteps()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            proceedWithNextSteps()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        CaptureCrash.initialize(this)
        Thread.setDefaultUncaughtExceptionHandler(CaptureCrash())

        ModDatabase.getDatabase()

        handleNxmIntent(intent)
        checkPermissionsAndProceed()
        askForNotifications()
    }

    /** The download service shows its progress as a notification; Android 13 and later ask first. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNxmIntent(intent)
    }

    private fun handleNxmIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "nxm") return
        intent.data = null
        NxmDownloads.handle(data.toString())
    }

    private fun checkPermissionsAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    "package:$packageName".toUri()
                )
                manageStorageLauncher.launch(intent)
            } else {
                proceedWithNextSteps()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                proceedWithNextSteps()
            }
        }
    }

    private fun proceedWithNextSteps() {
        lifecycleScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.Default) {
                UserManageAssets(applicationContext).onFirstLaunch()
            }
            launch(Dispatchers.IO) { UpstreamEngine.migrateCache(applicationContext) }

            val (width, height) = SystemInfo.services.deviceRealSize()

            setContent {
                OpenMWTheme(darkTheme = true) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val hideSystemBars by GameFilesPreferences.getPreference(GameFilesPreferences.HIDE_SYSTEM_BARS, false).collectAsState(initial = false)
                        val screenStayOn by GameFilesPreferences.getPreference(GameFilesPreferences.SCREEN_STAY_ON, false).collectAsState(initial = false)

                        LaunchedEffect(hideSystemBars) {
                            if (hideSystemBars) hideSystemBars(this@MainActivity) else showSystemBars(this@MainActivity)
                        }

                        LaunchedEffect(screenStayOn) {
                            if (screenStayOn) enableScreenStayOn(this@MainActivity) else disableScreenStayOn(this@MainActivity)
                        }

                        LaunchedEffect(Unit) {
                            launch(Dispatchers.IO) {
                                startObservingCodeGroup(this@MainActivity, lifecycleScope)
                            }
                        }

                        // The settings.cfg to watch belongs to the active profile
                        DisposableEffect(Profiles.revision) {
                            val observer = ConfigFileObserver(OpenMWPaths.SETTINGS_FILE)
                            observer.startWatching()
                            onDispose { observer.stopWatching() }
                        }

                        val showDialog = remember { mutableStateOf(true) }
                        val whatsNew by GameFilesPreferences.getPreference(GameFilesPreferences.WHATS_NEW_KEY, true).collectAsState(initial = false)

                        LaunchedEffect(Unit) {
                            // Wait for the saved preference so the first frame doesn't use its default.
                            val avoidInsertion = GameFilesPreferences.getPreference(GameFilesPreferences.AVOID_RESOLUTION_INSERTION, false).first()
                            if (!avoidInsertion) {
                                withContext(Dispatchers.IO) { updateResolutionInConfig(width, height) }
                            }
                        }

                        if (whatsNew) {
                            MyAlertDialog(showDialog = showDialog)
                        }

                        if (!UIStateManager.useNavmesh) {
                            RootNav()
                            MoeDialog()
                        } else {
                            BackgroundAnimation()
                            NavmeshScreen(onComplete = { })
                        }
                    }
                }
            }
        }
    }
}

fun startObservingCodeGroup(context: Context, scope: CoroutineScope) {
    scope.launch {
        GameFilesPreferences.getPreference(GameFilesPreferences.CODE_GROUP_KEY, "OpenMW").collect { codeGroup ->
            UIStateManager.tempCodeGroup = codeGroup
        }
    }
    scope.launch {
        GameFilesPreferences.getPreference(GameFilesPreferences.OPENMW_ENGINE_KEY, "upstream").collect { engine ->
            UIStateManager.forkEngineAvailable = BuildConfig.OPENMW_FORK
            UIStateManager.openmwEngine = if (BuildConfig.OPENMW_FORK) engine else "upstream"
        }
    }
    scope.launch {
        GameFilesPreferences.getPreference(GameFilesPreferences.IGNORE_CONTROLLER_KEY, false).collect { ignore ->
            UIStateManager.ignoreController = ignore
        }
    }
}
