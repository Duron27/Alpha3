package org.alpha3.launcher.ui.view

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface WebController {
    fun loadUrl(url: String)
}

/** A native web view showing [url]; [onController] hands back a handle for navigating it later. */
@Composable
expect fun PlatformWebView(url: String, modifier: Modifier, onController: (WebController) -> Unit)
