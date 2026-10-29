package org.alpha3.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import org.alpha3.launcher.globals.PlayBridge
import org.alpha3.launcher.utils.Platform as PlatformHost

@OptIn(ExperimentalFoundationApi::class)
fun MainViewController(
    onPlay: (() -> Unit)? = null,
    onResetSettings: (() -> Unit)? = null
) = ComposeUIViewController {
    PlatformHost.features = IosPlatformFeatures()
    // The launcher's backgrounds/icons load from userUI too, so install them before the first frame.
    remember { installDefaultUi() }
    PlayBridge.onPlay = onPlay
    PlayBridge.onResetSettings = onResetSettings
    App()
}
