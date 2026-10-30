package org.alpha3.launcher.mods.downloader

import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * The mod downloader on iOS. A free member's Nexus file page opens in an ASWebAuthenticationSession: it shares Safari's
 * sign-in, and the nxm:// link that "Slow download" leads to ends the session and comes straight back to the app
 * (no URL scheme has to be registered). While a list installs the screen stays on, and a background task asks iOS for
 * the time to finish what is running when the app is left.
 */
object IosModDownloaderHost : ModDownloaderHost {
    private var session: ASWebAuthenticationSession? = null
    private var backgroundTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    private val anchor = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
            UIApplication.sharedApplication.keyWindow ?: UIWindow()
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

    override fun openNexusPage(url: String) = start(url) { link ->
        if (link != null) NxmDownloads.handle(link) else NxmDownloads.skipCurrent()
    }

    override fun openSignIn(url: String) = start(url) {}

    override fun closeNexusPages() {
        session?.cancel()
        session = null
    }

    override fun setRunning(running: Boolean) {
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

    override fun openLink(url: String, label: String, fileName: String?) {
        NSURL.URLWithString(url)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }
}
