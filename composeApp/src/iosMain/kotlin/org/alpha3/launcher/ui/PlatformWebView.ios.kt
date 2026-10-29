package org.alpha3.launcher.ui.view

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(url: String, modifier: Modifier, onController: (WebController) -> Unit) {
    val webView = remember {
        WKWebView(frame = CGRectZero.readValue(), configuration = WKWebViewConfiguration())
    }
    UIKitView(
        factory = {
            val controller = object : WebController {
                override fun loadUrl(url: String) {
                    NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest.requestWithURL(it)) }
                }
            }
            controller.loadUrl(url)
            onController(controller)
            webView
        },
        modifier = modifier
    )
}
