package org.alpha3.launcher.mods.downloader

import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The mod downloader on iOS. A free member's Nexus file page opens in an ASWebAuthenticationSession: it shares Safari's
 * sign-in, and the nxm:// link that "Slow download" leads to ends the session and comes straight back to the app
 * (no URL scheme has to be registered). While a list installs the screen stays on, and a background task asks iOS for
 * the time to finish what is running when the app is left. The downloader calls in from its worker threads, and UIKit
 * only takes calls on the main thread, so every call moves there.
 */
object IosModDownloaderHost : ModDownloaderHost {
    private var session: ASWebAuthenticationSession? = null
    private var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
    }

    private fun start(url: String, onLink: (String?) -> Unit) {
        session?.cancel()
        val nsUrl = NSURL.URLWithString(url) ?: return
        session = ASWebAuthenticationSession(nsUrl, "nxm") { callbackUrl, _ ->
            session = null
            onLink(callbackUrl?.absoluteString)
        }.apply {
            presentationContextProvider = anchor
            prefersEphemeralWebBrowserSession = false
            start()
        }
    }

    override fun openNexusPage(url: String) = onMain {
        start(url) { link -> if (link != null) NxmDownloads.handle(link) else NxmDownloads.skipCurrent() }
    }

    override fun openSignIn(url: String) = onMain { start(url) {} }

    override fun closeNexusPages() = onMain {
        session?.cancel()
        session = null
    }

    override fun setRunning(running: Boolean) = onMain {
        val app = UIApplication.sharedApplication
        app.idleTimerDisabled = running
        if (running && backgroundTask == UIBackgroundTaskInvalid) {
            backgroundTask = app.beginBackgroundTaskWithExpirationHandler {
                app.endBackgroundTask(backgroundTask)
                backgroundTask = UIBackgroundTaskInvalid
            }
        } else if (!running && backgroundTask != UIBackgroundTaskInvalid) {
            app.endBackgroundTask(backgroundTask)
            backgroundTask = UIBackgroundTaskInvalid
        }
    }

    override fun openLink(url: String, label: String, fileName: String?) = onMain {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }
}
