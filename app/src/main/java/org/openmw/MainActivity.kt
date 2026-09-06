package org.openmw

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.InputDevice.SOURCE_GAMEPAD
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openmw.modDownloader.ModDatabase
import org.openmw.modDownloader.ModListManager
import org.openmw.ui.controls.UIStateManager
import org.openmw.ui.navigation.RootNav
import org.openmw.ui.theme.OpenMWTheme
import org.openmw.ui.view.BackgroundAnimation
import org.openmw.ui.view.MoeDialog
import org.openmw.ui.view.NavmeshScreen
import org.openmw.ui.view.currentDeviceRealSize
import org.openmw.ui.view.updateResolutionInConfig
import org.openmw.utils.CaptureCrash
import org.openmw.utils.ConfigFileObserver
import org.openmw.utils.GameFilesPreferences
import org.openmw.utils.GameFilesPreferences.getScreenStayOn
import org.openmw.utils.GameFilesPreferences.getSystemBars
import org.openmw.utils.GameFilesPreferences.readCodeGroup
import org.openmw.utils.MyAlertDialog
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

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        CaptureCrash.initialize(this)
        Thread.setDefaultUncaughtExceptionHandler(CaptureCrash())

        ModListManager.init(this)
        ModDatabase.getDatabase(this)

        checkPermissionsAndProceed()
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
            GameFilesPreferences.initialize(this@MainActivity)

            withContext(Dispatchers.Default) {
                UserManageAssets(applicationContext).onFirstLaunch()
            }

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val (width, height) = windowManager.currentDeviceRealSize()

            setContent {
                OpenMWTheme(darkTheme = true) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val hideSystemBars by getSystemBars(this@MainActivity).collectAsState(initial = false)
                        val screenStayOn by getScreenStayOn(this@MainActivity).collectAsState(initial = false)

                        SideEffect {
                            if (hideSystemBars) hideSystemBars(this@MainActivity) else showSystemBars(this@MainActivity)
                            if (screenStayOn) enableScreenStayOn(this@MainActivity) else disableScreenStayOn(this@MainActivity)
                        }

                        LaunchedEffect(Unit) {
                            launch(Dispatchers.IO) {
                                startObservingCodeGroup(this@MainActivity, lifecycleScope)
                                ConfigFileObserver(Constants.SETTINGS_FILE).startWatching()
                            }
                        }

                        val showDialog = remember { mutableStateOf(true) }
                        val avoidInsertion by GameFilesPreferences.readResolutionInsertion(this@MainActivity).collectAsState(initial = false)
                        val whatsNew by GameFilesPreferences.getWhatsNew(this@MainActivity).collectAsState(initial = false)

                        if (!avoidInsertion) {
                            updateResolutionInConfig(width, height)
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

fun isControllerConnected(context: Context): Boolean {
    val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    return inputManager.inputDeviceIds.any { id ->
        inputManager.getInputDevice(id)?.sources?.and(SOURCE_GAMEPAD) == SOURCE_GAMEPAD
    }
}

fun startObservingCodeGroup(context: Context, scope: CoroutineScope) {
    scope.launch {
        readCodeGroup(context).collect { codeGroup ->
            UIStateManager.tempCodeGroup = codeGroup
        }
    }
}
